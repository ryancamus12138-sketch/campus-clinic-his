package io.github.ryancamus.clinic.system.service;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 两步登录的中间凭证：口令已验证，但账号使用的是临时口令，必须先修改口令才能取得令牌。
 * <p>
 * 随机串存 Redis，5 分钟有效；完成时原子删除，只能使用一次。
 */
@Component
@RequiredArgsConstructor
public class ChallengeStore {

    static final Duration TTL = Duration.ofMinutes(5);
    private static final String KEY = "auth:challenge:";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final StringRedisTemplate redis;

    public String issue(Long userId, String purpose) {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        redis.opsForHash().putAll(KEY + token, Map.of("userId", String.valueOf(userId), "purpose", purpose));
        redis.expire(KEY + token, TTL);
        return token;
    }

    /** 读取但不消耗：新口令校验通过、保存之后再消耗 */
    public Optional<Map<String, String>> peek(String token, String purpose) {
        Map<Object, Object> raw = redis.opsForHash().entries(KEY + token);
        if (raw.isEmpty() || !purpose.equals(raw.get("purpose"))) {
            return Optional.empty();
        }
        Map<String, String> fields = new HashMap<>();
        raw.forEach((k, v) -> fields.put((String) k, (String) v));
        return Optional.of(fields);
    }

    /** 原子删除；返回 false 表示已被并发请求使用过 */
    public boolean consume(String token) {
        return Boolean.TRUE.equals(redis.delete(KEY + token));
    }
}
