package io.github.ryancamus.clinic.system.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.github.ryancamus.clinic.common.exception.BizException;
import io.github.ryancamus.clinic.common.result.PageResult;
import io.github.ryancamus.clinic.framework.security.LoginUser;
import io.github.ryancamus.clinic.framework.security.SecurityUtils;
import io.github.ryancamus.clinic.framework.security.TokenStore;
import io.github.ryancamus.clinic.framework.time.WallClock;
import io.github.ryancamus.clinic.system.convert.UserConvert;
import io.github.ryancamus.clinic.system.domain.dto.UserCreateDTO;
import io.github.ryancamus.clinic.system.domain.dto.UserUpdateDTO;
import io.github.ryancamus.clinic.system.domain.entity.SysUser;
import io.github.ryancamus.clinic.system.domain.query.UserQuery;
import io.github.ryancamus.clinic.system.domain.vo.RoleVO;
import io.github.ryancamus.clinic.system.domain.vo.UserSecretVO;
import io.github.ryancamus.clinic.system.domain.vo.UserVO;
import io.github.ryancamus.clinic.system.enums.SystemErrorCode;
import io.github.ryancamus.clinic.system.mapper.SysUserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 员工账号管理。
 * <ul>
 *     <li>初始口令和重置后的口令由系统随机生成、只显示一次，首次登录必须修改；</li>
 *     <li>三权分立：系统管理员与审计管理员不能由同一账号兼任；</li>
 *     <li>防止把自己锁在门外：不能停用自己，也不能移除自己的管理员角色。</li>
 * </ul>
 * 角色变更在用户下次刷新令牌时生效（访问令牌最长 30 分钟）。系统只有员工账号，患者不登录本系统。
 */
@Service
@RequiredArgsConstructor
public class UserService {

    static final String ROLE_ADMIN = "ADMIN";
    static final String ROLE_AUDITOR = "AUDITOR";

    private final SysUserMapper userMapper;
    private final UserConvert userConvert;
    private final PasswordPolicy passwordPolicy;
    private final PasswordEncoder passwordEncoder;
    private final TokenStore tokenStore;
    /** 口令修改时间用真实时钟，不受测试中可调的业务时钟影响 */
    private final WallClock wallClock;

    public PageResult<UserVO> page(UserQuery query) {
        LambdaQueryWrapper<SysUser> wrapper = Wrappers.<SysUser>lambdaQuery()
                .eq(query.userType() != null, SysUser::getUserType, query.userType())
                .and(StringUtils.hasText(query.keyword()), w -> w
                        .likeRight(SysUser::getUsername, query.keyword())
                        .or()
                        .likeRight(SysUser::getNickname, query.keyword()))
                .orderByDesc(SysUser::getId);
        Page<SysUser> page = userMapper.selectPage(Page.of(query.pageOrDefault(), query.sizeOrDefault()), wrapper);
        return new PageResult<>(page.getRecords().stream()
                .map(u -> userConvert.toVO(u).withRoleIds(userMapper.selectRoleIds(u.getId())))
                .toList(), page.getTotal(), page.getCurrent(), page.getSize());
    }

    public List<RoleVO> roles() {
        return userMapper.selectRoles().stream()
                .map(r -> new RoleVO(((Number) r.get("id")).longValue(), (String) r.get("role_code"),
                        (String) r.get("role_name")))
                .toList();
    }

    @Transactional
    public UserSecretVO create(UserCreateDTO dto) {
        Set<Long> roleIds = checkRoles(dto.roleIds());
        String temporary = passwordPolicy.generate();
        SysUser user = new SysUser();
        user.setUsername(dto.username());
        user.setNickname(dto.nickname().trim());
        user.setUserType(SysUser.TYPE_STAFF);
        user.setCampusId(dto.campusId());
        user.setDeptId(dto.deptId());
        user.setStatus(SysUser.STATUS_ENABLED);
        user.setPasswordHash(passwordEncoder.encode(temporary));
        user.setPasswordChangedAt(wallClock.now());
        user.setMustChangePassword(1);
        try {
            userMapper.insert(user);
        } catch (DuplicateKeyException e) {
            throw new BizException(SystemErrorCode.USERNAME_EXISTS);
        }
        roleIds.forEach(r -> userMapper.insertUserRole(user.getId(), r));
        return new UserSecretVO(user.getId(), user.getUsername(), temporary);
    }

    @Transactional
    public void update(Long id, UserUpdateDTO dto) {
        SysUser user = requireStaff(id);
        Set<Long> roleIds = checkRoles(dto.roleIds());
        LoginUser me = SecurityUtils.requireUser();
        if (me.getUserId().equals(id)) {
            boolean keepsAdmin = roleIds.contains(roleId(ROLE_ADMIN));
            if (dto.status() != SysUser.STATUS_ENABLED || (me.getRoles().contains(ROLE_ADMIN) && !keepsAdmin)) {
                throw new BizException(SystemErrorCode.SELF_LOCKOUT);
            }
        }
        user.setNickname(dto.nickname().trim());
        user.setCampusId(dto.campusId());
        user.setDeptId(dto.deptId());
        user.setStatus(dto.status());
        userMapper.updateById(user);
        userMapper.deleteUserRoles(id);
        roleIds.forEach(r -> userMapper.insertUserRole(id, r));
    }

    @Transactional
    public UserSecretVO resetPassword(Long id) {
        SysUser user = requireStaff(id);
        String temporary = passwordPolicy.generate();
        userMapper.updatePassword(id, passwordEncoder.encode(temporary), wallClock.now(), 1);
        tokenStore.clearLoginFailures(user.getUsername());
        return new UserSecretVO(id, user.getUsername(), temporary);
    }

    public void unlock(Long id) {
        tokenStore.clearLoginFailures(requireStaff(id).getUsername());
    }

    private SysUser requireStaff(Long id) {
        SysUser user = userMapper.selectById(id);
        if (user == null) {
            throw new BizException(SystemErrorCode.USER_NOT_FOUND);
        }
        if (!user.isStaff()) {
            throw new BizException(SystemErrorCode.NOT_STAFF);
        }
        return user;
    }

    private Set<Long> checkRoles(List<Long> requested) {
        Map<Long, String> codes = userMapper.selectRoles().stream().collect(Collectors.toMap(
                r -> ((Number) r.get("id")).longValue(), r -> (String) r.get("role_code")));
        Set<Long> ids = new LinkedHashSet<>(requested);
        for (Long id : ids) {
            if (!codes.containsKey(id)) {
                throw new BizException(SystemErrorCode.ROLE_NOT_FOUND);
            }
        }
        Set<String> chosen = ids.stream().map(codes::get).collect(Collectors.toSet());
        if (chosen.contains(ROLE_ADMIN) && chosen.contains(ROLE_AUDITOR)) {
            throw new BizException(SystemErrorCode.ROLE_CONFLICT);
        }
        return ids;
    }

    private Long roleId(String code) {
        return userMapper.selectRoles().stream().filter(r -> code.equals(r.get("role_code")))
                .map(r -> ((Number) r.get("id")).longValue()).findFirst().orElse(-1L);
    }
}
