package io.github.ryancamus.clinic.framework.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 从 Authorization 头解析访问令牌并建立认证上下文。
 * <p>
 * 令牌无效时不直接返回错误，而是保持未认证状态，交给后续的授权规则决定：
 * 公开接口照常放行，受保护接口由入口点统一返回 401。
 * <p>
 * 注意：本类不注册为 Spring Bean，否则 Spring Boot 会把它再注册一次成普通 Servlet 过滤器，导致执行两遍。
 */
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String BEARER = "Bearer ";

    private final JwtTokenProvider tokenProvider;
    private final TokenStore tokenStore;

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain chain) throws ServletException, IOException {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header != null && header.startsWith(BEARER)) {
            tokenProvider.parse(header.substring(BEARER.length()))
                    .filter(parsed -> !tokenStore.isBlacklisted(parsed.jti()))
                    .ifPresent(parsed -> {
                        var authentication = new UsernamePasswordAuthenticationToken(
                                parsed.user(), parsed, parsed.user().getAuthorities());
                        SecurityContextHolder.getContext().setAuthentication(authentication);
                    });
        }
        chain.doFilter(request, response);
    }
}
