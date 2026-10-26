package io.github.ryancamus.clinic.system.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import io.github.ryancamus.clinic.framework.security.LoginUser;
import io.github.ryancamus.clinic.system.domain.entity.SysUser;
import io.github.ryancamus.clinic.system.mapper.SysUserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.Optional;

/**
 * 从数据库加载用户及其角色、权限。登录校验（按用户名）和刷新令牌（按 ID）共用。
 */
@Service
@RequiredArgsConstructor
public class LoginUserLoader implements UserDetailsService {

    private final SysUserMapper userMapper;

    @Override
    public UserDetails loadUserByUsername(String username) {
        SysUser user = userMapper.selectOne(Wrappers.<SysUser>lambdaQuery().eq(SysUser::getUsername, username));
        if (user == null) {
            throw new UsernameNotFoundException(username);
        }
        return toLoginUser(user, true);
    }

    public Optional<LoginUser> loadById(Long userId) {
        return Optional.ofNullable(userMapper.selectById(userId)).map(u -> toLoginUser(u, false));
    }

    private LoginUser toLoginUser(SysUser user, boolean withPassword) {
        return LoginUser.builder()
                .userId(user.getId())
                .username(user.getUsername())
                .password(withPassword ? user.getPasswordHash() : null)
                .nickname(user.getNickname())
                .userType(user.getUserType())
                .campusId(user.getCampusId())
                .roles(new LinkedHashSet<>(userMapper.selectRoleCodes(user.getId())))
                .permissions(new LinkedHashSet<>(userMapper.selectPermissionCodes(user.getId())))
                .enabled(user.isEnabled())
                .build();
    }
}
