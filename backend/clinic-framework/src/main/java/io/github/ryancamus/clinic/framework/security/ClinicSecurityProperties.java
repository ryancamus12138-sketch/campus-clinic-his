package io.github.ryancamus.clinic.framework.security;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;
import java.util.List;

/**
 * 安全相关配置，前缀 clinic.security。
 *
 * @param permitUrls         无需登录即可访问的路径：登录相关接口、接口文档（生产环境关闭）、健康检查和监控指标。
 *                           健康检查经 Nginx 只对内网开放，监控指标不经 Nginx 对外提供，只能在容器网络内采集；
 *                           生产环境后端端口不映射到宿主机
 * @param corsAllowedOrigins 允许跨域的前端地址；生产环境前后端同域经 Nginx 转发，可留空
 * @param maxLoginFailures   连续登录失败多少次后锁定
 * @param lockDuration       锁定时长
 * @param jwt                令牌配置
 * @param password           口令策略
 */
@ConfigurationProperties(prefix = "clinic.security")
public record ClinicSecurityProperties(
        @DefaultValue({"/api/auth/login", "/api/auth/refresh", "/api/auth/password/expired",
                "/api/auth/options", "/v3/api-docs/**", "/swagger-ui/**",
                "/swagger-ui.html", "/actuator/health/**", "/actuator/prometheus"})
        List<String> permitUrls,
        @DefaultValue List<String> corsAllowedOrigins,
        @DefaultValue("5") int maxLoginFailures,
        @DefaultValue("15m") Duration lockDuration,
        @DefaultValue Jwt jwt,
        @DefaultValue Password password) {

    /**
     * @param secret     HMAC 密钥，至少 32 字节；生产环境必须通过环境变量注入
     * @param issuer     签发方
     * @param accessTtl  访问令牌有效期，短期
     * @param refreshTtl 刷新令牌有效期，存 Redis，一次性使用
     */
    public record Jwt(
            String secret,
            @DefaultValue("campus-clinic-his") String issuer,
            @DefaultValue("30m") Duration accessTtl,
            @DefaultValue("7d") Duration refreshTtl) {
    }

    /**
     * 口令复杂度。
     *
     * @param minLength  最小长度
     * @param minClasses 大写、小写、数字、符号四类中至少包含几类
     */
    public record Password(
            @DefaultValue("10") int minLength,
            @DefaultValue("3") int minClasses) {
    }
}
