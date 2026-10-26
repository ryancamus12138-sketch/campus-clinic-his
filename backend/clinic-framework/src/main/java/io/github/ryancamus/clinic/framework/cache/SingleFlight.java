package io.github.ryancamus.clinic.framework.cache;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;

/**
 * 同一个 key 同一时刻只加载一次，其余调用方等待同一个结果（防缓存击穿）。
 * <p>
 * 为什么不用 Caffeine 的 {@code cache.get(key, loader)}：它在 ConcurrentHashMap 的 synchronized 块里执行 loader。
 * loader 查数据库时，JDK 21 的虚拟线程会被“钉住”在载体线程上；2 核机器只有 2 个载体线程，
 * 压测中出现过所有请求挂起 60 秒的情况。这里 loader 在锁外执行，等待方阻塞在 CompletableFuture 上，不占锁。
 */
public final class SingleFlight<K, V> {

    private static final long WAIT_SECONDS = 10;

    private final ConcurrentHashMap<K, CompletableFuture<V>> inFlight = new ConcurrentHashMap<>();

    public V load(K key, Function<K, V> loader) {
        CompletableFuture<V> mine = new CompletableFuture<>();
        CompletableFuture<V> existing = inFlight.putIfAbsent(key, mine);
        if (existing != null) {
            return await(existing);
        }
        try {
            V value = loader.apply(key);
            mine.complete(value);
            return value;
        } catch (RuntimeException | Error e) {
            mine.completeExceptionally(e);
            throw e;
        } finally {
            inFlight.remove(key, mine);
        }
    }

    private V await(CompletableFuture<V> future) {
        try {
            return future.get(WAIT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while waiting for in-flight load", e);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof RuntimeException re) {
                throw re;
            }
            throw new IllegalStateException(e.getCause());
        } catch (TimeoutException e) {
            throw new IllegalStateException("in-flight load timed out", e);
        }
    }
}
