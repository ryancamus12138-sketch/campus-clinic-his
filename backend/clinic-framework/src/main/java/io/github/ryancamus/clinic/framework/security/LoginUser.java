package io.github.ryancamus.clinic.framework.security;

import lombok.Builder;
import lombok.Getter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 当前登录用户。登录时由数据库加载，之后每次请求从访问令牌还原，不再查库。
 * <p>
 * 权限分两类：角色（ROLE_ 前缀，用于粗粒度判断）和权限码（如 charge:settle，用于接口级控制）。
 */
@Getter
@Builder(toBuilder = true)
public class LoginUser implements UserDetails {

    public static final String ROLE_PREFIX = "ROLE_";

    private final Long userId;
    private final String username;
    /** 仅登录校验时有值，令牌中不包含 */
    private final String password;
    private final String nickname;
    /** 用户类型：1 员工 */
    private final Integer userType;
    private final Long campusId;
    @Builder.Default
    private final Set<String> roles = Set.of();
    @Builder.Default
    private final Set<String> permissions = Set.of();
    @Builder.Default
    private final boolean enabled = true;

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        Set<GrantedAuthority> authorities = new LinkedHashSet<>();
        if (roles != null) {
            roles.forEach(r -> authorities.add(new SimpleGrantedAuthority(ROLE_PREFIX + r)));
        }
        if (permissions != null) {
            permissions.forEach(p -> authorities.add(new SimpleGrantedAuthority(p)));
        }
        return authorities;
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }
}
