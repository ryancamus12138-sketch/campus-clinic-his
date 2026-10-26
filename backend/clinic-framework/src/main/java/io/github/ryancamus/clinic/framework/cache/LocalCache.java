package io.github.ryancamus.clinic.framework.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import java.time.Duration;
import java.util.function.Function;

/**
 * 本地缓存 = Caffeine 存储 + {@link SingleFlight} 加载。
 * <p>
 * 未命中时同一 key 只有一个线程去查数据库，并且不在任何锁内执行查询。
 * 加载结果为 null 时不缓存（例如号源不存在），下次仍会查询。
 */
public final class LocalCache<K, V> {

    private final Cache<K, V> cache;
    private final SingleFlight<K, V> singleFlight = new SingleFlight<>();

    private LocalCache(Cache<K, V> cache) {
        this.cache = cache;
    }

    public static <K, V> LocalCache<K, V> of(long maximumSize, Duration ttl) {
        return new LocalCache<>(Caffeine.newBuilder().maximumSize(maximumSize).expireAfterWrite(ttl).build());
    }

    public V get(K key, Function<K, V> loader) {
        V cached = cache.getIfPresent(key);
        if (cached != null) {
            return cached;
        }
        V loaded = singleFlight.load(key, loader);
        if (loaded != null) {
            cache.put(key, loaded);
        }
        return loaded;
    }

    public void invalidate(K key) {
        cache.invalidate(key);
    }

    public void invalidateAll() {
        cache.invalidateAll();
    }
}
