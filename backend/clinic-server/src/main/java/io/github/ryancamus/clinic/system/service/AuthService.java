package io.github.ryancamus.clinic.system.service;

import io.github.ryancamus.clinic.audit.api.AuditApi;
import io.github.ryancamus.clinic.common.exception.BizException;
import io.github.ryancamus.clinic.framework.security.ClinicSecurityProperties;
import io.github.ryancamus.clinic.framework.security.JwtTokenProvider;
import io.github.ryancamus.clinic.framework.security.LoginUser;
import io.github.ryancamus.clinic.framework.security.SecurityUtils;
import io.github.ryancamus.clinic.framework.security.TokenStore;
import io.github.ryancamus.clinic.framework.time.WallClock;
import io.github.ryancamus.clinic.system.domain.dto.LoginDTO;
import io.github.ryancamus.clinic.system.domain.entity.SysUser;
import io.github.ryancamus.clinic.system.domain.vo.CurrentUserVO;
import io.github.ryancamus.clinic.system.domain.vo.LoginResultVO;
import io.github.ryancamus.clinic.system.domain.vo.TokenVO;
import io.github.ryancamus.clinic.system.enums.SystemErrorCode;
import io.github.ryancamus.clinic.system.mapper.SysUserMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * 登录、刷新、退出、改口令。
 * <p>
 * 访问令牌短期有效、无状态；刷新令牌存 Redis、一次性使用（每次刷新都换新），
 * 被盗用的刷新令牌在合法用户下一次刷新后即失效。
 * <p>
 * 新开账号或被管理员重置口令后，首次登录分两步：口令 → 修改临时口令。两步之间用一次性的 challenge 衔接，
 * 中途放弃不会得到任何令牌。
 */
@Slf4j
@Service
public class AuthService {

    private final AuthenticationManager authenticationManager;
    private final JwtTokenProvider tokenProvider;
    private final TokenStore tokenStore;
    private final LoginUserLoader userLoader;
    private final SysUserMapper userMapper;
    private final ClinicSecurityProperties securityProperties;
    private final Clock clock;
    private final AuditApi auditApi;
    private final ChallengeStore challenges;
    private final PasswordPolicy passwordPolicy;
    private final PasswordEncoder passwordEncoder;
    private final Counter loginFailures;
    /** 口令修改时间用真实时钟，不受测试中可调的业务时钟影响 */
    private final WallClock wallClock;

    public AuthService(AuthenticationManager authenticationManager, JwtTokenProvider tokenProvider,
                       TokenStore tokenStore, LoginUserLoader userLoader, SysUserMapper userMapper,
                       ClinicSecurityProperties securityProperties, Clock clock, AuditApi auditApi,
                       ChallengeStore challenges, PasswordPolicy passwordPolicy, PasswordEncoder passwordEncoder,
                       MeterRegistry meterRegistry, WallClock wallClock) {
        this.wallClock = wallClock;
        this.authenticationManager = authenticationManager;
        this.tokenProvider = tokenProvider;
        this.tokenStore = tokenStore;
        this.userLoader = userLoader;
        this.userMapper = userMapper;
        this.securityProperties = securityProperties;
        this.clock = clock;
        this.auditApi = auditApi;
        this.challenges = challenges;
        this.passwordPolicy = passwordPolicy;
        this.passwordEncoder = passwordEncoder;
        // 口令错误次数；短时间内激增通常是撞库或暴力破解
        this.loginFailures = Counter.builder("clinic.auth.login.failures")
                .description("登录失败次数").register(meterRegistry);
    }

    public LoginResultVO login(LoginDTO dto) {
        String username = dto.username().trim();
        if (tokenStore.loginFailures(username) >= securityProperties.maxLoginFailures()) {
            auditApi.recordAs(null, username, "LOGIN", "登录", false, "账号已锁定");
            throw new BizException(SystemErrorCode.ACCOUNT_LOCKED);
        }
        Authentication authentication;
        try {
            authentication = authenticationManager.authenticate(
                    UsernamePasswordAuthenticationToken.unauthenticated(username, dto.password()));
        } catch (DisabledException e) {
            auditApi.recordAs(null, username, "LOGIN", "登录", false, "账号已停用");
            throw new BizException(SystemErrorCode.ACCOUNT_DISABLED);
        } catch (BadCredentialsException e) {
            loginFailures.increment();
            long failures = tokenStore.recordLoginFailure(username, securityProperties.lockDuration());
            log.warn("登录失败 username={} failures={}", username, failures);
            auditApi.recordAs(null, username, "LOGIN", "登录", false, "用户名或密码错误，连续失败 " + failures + " 次");
            throw new BizException(SystemErrorCode.BAD_CREDENTIALS);
        }
        tokenStore.clearLoginFailures(username);
        LoginUser user = (LoginUser) authentication.getPrincipal();
        // 登录页选择的身份只用于核对：账号不具备该身份就不发令牌；权限仍取自账号在数据库中的角色
        if (dto.role() != null && !user.getRoles().contains(dto.role())) {
            auditApi.recordAs(user.getUserId(), username, "LOGIN", "登录", false, "账号不具备所选身份 " + dto.role());
            throw new BizException(SystemErrorCode.ROLE_NOT_GRANTED);
        }
        SysUser account = userMapper.selectById(user.getUserId());
        if (passwordChangeRequired(account)) {
            return LoginResultVO.next(LoginResultVO.STEP_PASSWORD_CHANGE,
                    challenges.issue(account.getId(), LoginResultVO.STEP_PASSWORD_CHANGE));
        }
        return complete(account, "口令");
    }

    /** 登录过程中修改临时口令，然后完成登录 */
    public LoginResultVO changeExpiredPassword(String challenge, String newPassword) {
        Map<String, String> c = challenges.peek(challenge, LoginResultVO.STEP_PASSWORD_CHANGE)
                .orElseThrow(() -> new BizException(SystemErrorCode.CHALLENGE_INVALID));
        SysUser account = requireUser(Long.valueOf(c.get("userId")));
        applyNewPassword(account, newPassword);
        if (!challenges.consume(challenge)) {
            throw new BizException(SystemErrorCode.CHALLENGE_INVALID);
        }
        auditApi.recordAs(account.getId(), account.getUsername(), "PASSWORD_CHANGE", "修改口令", true, "登录时强制修改");
        return complete(account, "口令");
    }

    public TokenVO refresh(String refreshToken) {
        Long userId = tokenStore.consumeRefreshToken(refreshToken).orElseThrow(() -> {
            // 已使用过或伪造的刷新令牌：可能是令牌被盗后的重放
            auditApi.recordAs(null, null, "TOKEN_REFRESH", "刷新令牌", false, "刷新令牌无效或已使用");
            return new BizException(SystemErrorCode.REFRESH_TOKEN_INVALID);
        });
        // 重新查库：角色变更、账号停用在刷新时立即生效
        LoginUser user = userLoader.loadById(userId)
                .filter(LoginUser::isEnabled)
                .orElseThrow(() -> new BizException(SystemErrorCode.REFRESH_TOKEN_INVALID));
        return issueTokens(user);
    }

    public void logout(String refreshToken) {
        SecurityUtils.currentUser().ifPresent(u ->
                auditApi.recordAs(u.getUserId(), u.getUsername(), "LOGOUT", "退出登录", true, null));
        SecurityUtils.currentToken().ifPresent(token ->
                tokenStore.blacklist(token.jti(), Duration.between(clock.instant(), token.expiresAt())));
        if (refreshToken != null && !refreshToken.isBlank()) {
            tokenStore.revokeRefreshToken(refreshToken);
        }
    }

    /** 已登录用户修改自己的口令 */
    public void changePassword(String oldPassword, String newPassword) {
        LoginUser current = SecurityUtils.requireUser();
        SysUser account = requireUser(current.getUserId());
        if (!passwordEncoder.matches(oldPassword, account.getPasswordHash())) {
            auditApi.record("PASSWORD_CHANGE", "修改口令", null, false, "原口令不正确");
            throw new BizException(SystemErrorCode.OLD_PASSWORD_WRONG);
        }
        applyNewPassword(account, newPassword);
        auditApi.record("PASSWORD_CHANGE", "修改口令", null, true, null);
    }

    public CurrentUserVO currentUser() {
        LoginUser user = SecurityUtils.requireUser();
        return new CurrentUserVO(user.getUserId(), user.getUsername(), user.getNickname(), user.getUserType(),
                user.getCampusId(), user.getRoles(), user.getPermissions());
    }

    // ------------------------------------------------------------------ 内部步骤

    private LoginResultVO complete(SysUser account, String method) {
        LoginUser user = userLoader.loadById(account.getId())
                .filter(LoginUser::isEnabled)
                .orElseThrow(() -> new BizException(SystemErrorCode.ACCOUNT_DISABLED));
        userMapper.updateLastLoginTime(user.getUserId(), LocalDateTime.now(clock));
        auditApi.recordAs(user.getUserId(), user.getUsername(), "LOGIN", "登录", true, method);
        return LoginResultVO.done(issueTokens(user));
    }

    /** 新开账号或被管理员重置后的临时口令，首次登录必须先改 */
    private static boolean passwordChangeRequired(SysUser account) {
        return account.getMustChangePassword() != null && account.getMustChangePassword() == 1;
    }

    private void applyNewPassword(SysUser account, String newPassword) {
        passwordPolicy.check(account.getUsername(), newPassword);
        if (passwordEncoder.matches(newPassword, account.getPasswordHash())) {
            throw new BizException(SystemErrorCode.PASSWORD_REUSED);
        }
        userMapper.updatePassword(account.getId(), passwordEncoder.encode(newPassword), wallClock.now(), 0);
    }

    private SysUser requireUser(Long id) {
        SysUser account = userMapper.selectById(id);
        if (account == null) {
            throw new BizException(SystemErrorCode.CHALLENGE_INVALID);
        }
        return account;
    }

    private TokenVO issueTokens(LoginUser user) {
        JwtTokenProvider.AccessToken access = tokenProvider.createAccessToken(user);
        String refresh = tokenStore.issueRefreshToken(user.getUserId(), securityProperties.jwt().refreshTtl());
        long expiresIn = Duration.between(clock.instant(), access.expiresAt()).toSeconds();
        return TokenVO.bearer(access.token(), refresh, expiresIn);
    }
}
