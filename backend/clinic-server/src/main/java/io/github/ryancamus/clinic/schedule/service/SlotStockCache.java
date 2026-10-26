package io.github.ryancamus.clinic.schedule.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * 号源的 Redis 闸门：在请求到达数据库之前，用 Lua 脚本原子地完成
 * “是否已预约 → 是否还有余量 → 扣减 → 记录患者”，把注定失败的请求挡在数据库之外。
 * <p>
 * Redis 只负责削峰，不是正确性的来源：最终是否成功由数据库的条件更新和唯一索引决定。
 * 即使 Redis 主从切换丢了扣减记录、放进了多余的请求，数据库也不会超卖。
 * <p>
 * 键：slot:{id}:stock（剩余量）、slot:{id}:users（已占用号源的患者 ID 集合）。
 * 花括号是 Redis Cluster 的哈希标签，保证两个键落在同一分片，Lua 脚本才能同时操作。
 */
@Component
@RequiredArgsConstructor
public class SlotStockCache {

    public static final long NOT_WARMED = -3;
    public static final long DUPLICATE = -2;
    public static final long SOLD_OUT = -1;

    private static final RedisScript<Long> ACQUIRE = new DefaultRedisScript<>("""
            if redis.call('EXISTS', KEYS[1]) == 0 then return -3 end
            if redis.call('SISMEMBER', KEYS[2], ARGV[1]) == 1 then return -2 end
            local left = tonumber(redis.call('GET', KEYS[1]))
            if left <= 0 then return -1 end
            redis.call('DECR', KEYS[1])
            redis.call('SADD', KEYS[2], ARGV[1])
            return left - 1
            """, Long.class);

    /** 只有患者确实在集合中时才归还，避免重复归还使余量虚增 */
    private static final RedisScript<Long> RELEASE = new DefaultRedisScript<>("""
            if redis.call('EXISTS', KEYS[1]) == 0 then return -3 end
            if redis.call('SREM', KEYS[2], ARGV[1]) == 1 then
              return redis.call('INCR', KEYS[1])
            end
            return -2
            """, Long.class);

    private static final RedisScript<Long> RETURN_STOCK = new DefaultRedisScript<>("""
            if redis.call('EXISTS', KEYS[1]) == 0 then return -3 end
            return redis.call('INCR', KEYS[1])
            """, Long.class);

    /** 预热：键已存在则不覆盖（并发预热时只有第一个生效） */
    private static final RedisScript<Long> WARM = new DefaultRedisScript<>("""
            if redis.call('EXISTS', KEYS[1]) == 1 then return 0 end
            local ttl = tonumber(ARGV[2])
            redis.call('SET', KEYS[1], ARGV[1], 'EX', ttl)
            redis.call('DEL', KEYS[2])
            for i = 3, #ARGV do redis.call('SADD', KEYS[2], ARGV[i]) end
            redis.call('EXPIRE', KEYS[2], ttl)
            return 1
            """, Long.class);

    /** 对账修正：以数据库为准整体重建 */
    private static final RedisScript<Long> REBUILD = new DefaultRedisScript<>("""
            local ttl = tonumber(ARGV[2])
            redis.call('SET', KEYS[1], ARGV[1], 'EX', ttl)
            redis.call('DEL', KEYS[2])
            for i = 3, #ARGV do redis.call('SADD', KEYS[2], ARGV[i]) end
            redis.call('EXPIRE', KEYS[2], ttl)
            return 1
            """, Long.class);

    private final StringRedisTemplate redis;

    /**
     * 本实例看到“已约满”的号源，1 秒内的后续请求直接拒绝，连 Redis 都不访问。
     * 有名额被释放时本实例立即清除标记；其他实例最多 1 秒后恢复，期间用户刷新重试即可。
     */
    private final Cache<Long, Boolean> soldOut = Caffeine.newBuilder()
            .maximumSize(10_000).expireAfterWrite(Duration.ofSeconds(1)).build();

    public long tryAcquire(Long slotId, Long patientId) {
        if (soldOut.getIfPresent(slotId) != null) {
            return SOLD_OUT;
        }
        Long result = redis.execute(ACQUIRE, keys(slotId), String.valueOf(patientId));
        long gate = result == null ? NOT_WARMED : result;
        if (gate == SOLD_OUT) {
            soldOut.put(slotId, Boolean.TRUE);
        }
        return gate;
    }

    public void release(Long slotId, Long patientId) {
        redis.execute(RELEASE, keys(slotId), String.valueOf(patientId));
        soldOut.invalidate(slotId);
    }

    /**
     * 只归还余量、保留患者标记。用于数据库判定“该患者已有有效预约”的情况：
     * 患者确实占着号源（集合里应该有他），只是这次的 Redis 扣减是多余的。
     */
    public void returnStock(Long slotId) {
        redis.execute(RETURN_STOCK, List.of(stockKey(slotId)));
        soldOut.invalidate(slotId);
    }

    public boolean warm(Long slotId, int remaining, Collection<Long> activePatientIds, Duration ttl) {
        Long result = redis.execute(WARM, keys(slotId), args(remaining, activePatientIds, ttl));
        return result != null && result == 1L;
    }

    public void rebuild(Long slotId, int remaining, Collection<Long> activePatientIds, Duration ttl) {
        redis.execute(REBUILD, keys(slotId), args(remaining, activePatientIds, ttl));
        soldOut.invalidate(slotId);
    }

    /** 读取当前余量；未预热返回 null */
    public Integer peekRemaining(Long slotId) {
        String value = redis.opsForValue().get(stockKey(slotId));
        return value == null ? null : Integer.valueOf(value);
    }

    public List<Integer> peekRemaining(List<Long> slotIds) {
        List<String> values = redis.opsForValue().multiGet(slotIds.stream().map(SlotStockCache::stockKey).toList());
        List<Integer> result = new ArrayList<>(slotIds.size());
        for (int i = 0; i < slotIds.size(); i++) {
            String v = values == null ? null : values.get(i);
            result.add(v == null ? null : Integer.valueOf(v));
        }
        return result;
    }

    public void evict(Long slotId) {
        redis.delete(keys(slotId));
        soldOut.invalidate(slotId);
    }

    private static List<String> keys(Long slotId) {
        return List.of(stockKey(slotId), "slot:{" + slotId + "}:users");
    }

    private static String stockKey(Long slotId) {
        return "slot:{" + slotId + "}:stock";
    }

    private static Object[] args(int remaining, Collection<Long> patientIds, Duration ttl) {
        List<String> args = new ArrayList<>(patientIds.size() + 2);
        args.add(String.valueOf(remaining));
        args.add(String.valueOf(Math.max(60, ttl.toSeconds())));
        patientIds.forEach(id -> args.add(String.valueOf(id)));
        return args.toArray();
    }
}
