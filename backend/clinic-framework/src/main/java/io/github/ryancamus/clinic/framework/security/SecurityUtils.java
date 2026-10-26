package io.github.ryancamus.clinic.framework.security;

import io.github.ryancamus.clinic.common.exception.BizException;
import io.github.ryancamus.clinic.common.exception.CommonErrorCode;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Optional;

/**
 * 读取当前登录用户。业务代码通过它获取身份，而不是相信前端传来的用户 ID 或患者 ID，
 * 这是防止越权访问（IDOR）的关键。
 */
public final class SecurityUtils {

    private SecurityUtils() {
    }

    public static Optional<LoginUser> currentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof LoginUser user) {
            return Optional.of(user);
        }
        return Optional.empty();
    }

    public static LoginUser requireUser() {
        return currentUser().orElseThrow(() -> new BizException(CommonErrorCode.UNAUTHORIZED));
    }

    public static Long currentUserIdOrNull() {
        return currentUser().map(LoginUser::getUserId).orElse(null);
    }

    /** 当前请求携带的访问令牌信息，退出登录时用于拉黑 */
    public static Optional<JwtTokenProvider.ParsedToken> currentToken() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getCredentials() instanceof JwtTokenProvider.ParsedToken token) {
            return Optional.of(token);
        }
        return Optional.empty();
    }
}
