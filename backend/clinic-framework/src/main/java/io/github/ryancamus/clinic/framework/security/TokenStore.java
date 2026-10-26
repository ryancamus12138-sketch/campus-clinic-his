package io.github.ryancamus.clinic.framework.security;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.Optional;

/**
 * 基于 Redis 的令牌状态存储。
 * <ul>
 *     <li>刷新令牌：随机串，服务端保存，一次性使用。用 GETDEL 原子取出并删除，
 *     两个并发刷新请求只有一个能拿到，避免同一刷新令牌被重放。</li>
 *     <li>访问令牌黑名单：退出登录后把 jti 加入黑名单，存活到令牌原本的过期时间。</li>
 *     <li>登录失败计数：连续失败达到阈值后锁定账号一段时间，防止暴力破解。</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class TokenStore {

    private static final String REFRESH_KEY = "auth:refresh:";
    private static final String BLACKLIST_KEY = "auth:blacklist:";
    private static final String LOGIN_FAIL_KEY = "auth:fail:";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final StringRedisTemplate redis;

    public String issueRefreshToken(Long userId, Duration ttl) {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        redis.opsForValue().set(REFRESH_KEY + token, String.valueOf(userId), ttl);
        return token;
    }

    /** 原子地取出并作废刷新令牌，返回其所属用户 */
    public Optional<Long> consumeRefreshToken(String token) {
        String userId = redis.opsForValue().getAndDelete(REFRESH_KEY + token);
        return Optional.ofNullable(userId).map(Long::valueOf);
    }

    public void revokeRefreshToken(String token) {
        redis.delete(REFRESH_KEY + token);
    }

    public void blacklist(String jti, Duration remaining) {
        if (!remaining.isNegative() && !remaining.isZero()) {
            redis.opsForValue().set(BLACKLIST_KEY + jti, "1", remaining);
        }
    }

    public boolean isBlacklisted(String jti) {
        return Boolean.TRUE.equals(redis.hasKey(BLACKLIST_KEY + jti));
    }

    /** 记录一次失败并返回窗口内的累计次数；首次失败时设置过期时间 */
    public long recordLoginFailure(String username, Duration window) {
        String key = LOGIN_FAIL_KEY + username;
        Long count = redis.opsForValue().increment(key);
        if (count != null && count == 1L) {
            redis.expire(key, window);
        }
        return count == null ? 0 : count;
    }

    public long loginFailures(String username) {
        String value = redis.opsForValue().get(LOGIN_FAIL_KEY + username);
        return value == null ? 0 : Long.parseLong(value);
    }

    public void clearLoginFailures(String username) {
        redis.delete(LOGIN_FAIL_KEY + username);
    }
}
