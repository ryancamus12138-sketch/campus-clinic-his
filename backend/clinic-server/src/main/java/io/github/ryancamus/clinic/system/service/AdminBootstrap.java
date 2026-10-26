package io.github.ryancamus.clinic.system.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import io.github.ryancamus.clinic.system.domain.entity.SysUser;
import io.github.ryancamus.clinic.system.mapper.SysUserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 生产环境初始化管理员：设置环境变量 ADMIN_INITIAL_PASSWORD 后首次启动时创建 admin 账号。
 * 已存在 admin 时什么都不做，因此密码不会在重启时被覆盖。
 * <p>
 * 演示账号只在 dev 环境的 Flyway 数据里，生产库没有任何默认密码。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AdminBootstrap implements ApplicationRunner {

    private static final String ADMIN_USERNAME = "admin";
    private static final long ADMIN_ROLE_ID = 1L;
    private static final int MIN_PASSWORD_LENGTH = 10;

    private final SysUserMapper userMapper;
    private final PasswordEncoder passwordEncoder;

    @Value("${clinic.bootstrap.admin-password:}")
    private String initialPassword;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (!StringUtils.hasText(initialPassword)) {
            return;
        }
        boolean exists = userMapper.exists(Wrappers.<SysUser>lambdaQuery().eq(SysUser::getUsername, ADMIN_USERNAME));
        if (exists) {
            return;
        }
        if (initialPassword.length() < MIN_PASSWORD_LENGTH) {
            throw new IllegalStateException("ADMIN_INITIAL_PASSWORD 至少需要 " + MIN_PASSWORD_LENGTH + " 位");
        }
        SysUser admin = new SysUser();
        admin.setUsername(ADMIN_USERNAME);
        admin.setPasswordHash(passwordEncoder.encode(initialPassword));
        admin.setNickname("系统管理员");
        admin.setUserType(SysUser.TYPE_STAFF);
        admin.setStatus(SysUser.STATUS_ENABLED);
        // 初始口令写在部署密钥文件里，首次登录必须修改
        admin.setMustChangePassword(1);
        userMapper.insert(admin);
        userMapper.insertUserRole(admin.getId(), ADMIN_ROLE_ID);
        log.info("已创建初始管理员账号 admin，首次登录必须修改口令");
    }
}
