package io.github.ryancamus.clinic.framework.cache;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SingleFlightTest {

    @Test
    void concurrentCallersShareOneLoad() throws Exception {
        SingleFlight<String, Integer> flight = new SingleFlight<>();
        AtomicInteger loads = new AtomicInteger();
        CountDownLatch release = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < 200; i++) {
                results.add(pool.submit(() -> flight.load("slot", k -> {
                    loads.incrementAndGet();
                    try {
                        release.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return 42;
                })));
            }
            Thread.sleep(200);
            release.countDown();
            for (Future<Integer> f : results) {
                assertThat(f.get()).isEqualTo(42);
            }
        }
        assertThat(loads.get()).as("200 个并发调用只加载一次").isLessThanOrEqualTo(2);
    }

    @Test
    void failureIsPropagatedAndNotCached() {
        SingleFlight<String, Integer> flight = new SingleFlight<>();
        assertThatThrownBy(() -> flight.load("k", k -> {
            throw new IllegalArgumentException("boom");
        })).isInstanceOf(IllegalArgumentException.class);
        assertThat(flight.load("k", k -> 7)).isEqualTo(7);
    }

    @Test
    void localCacheDoesNotCacheNull() {
        LocalCache<String, String> cache = LocalCache.of(10, Duration.ofMinutes(1));
        AtomicInteger loads = new AtomicInteger();
        assertThat(cache.get("missing", k -> {
            loads.incrementAndGet();
            return null;
        })).isNull();
        assertThat(cache.get("missing", k -> {
            loads.incrementAndGet();
            return "now-present";
        })).isEqualTo("now-present");
        assertThat(cache.get("missing", k -> "should-not-load")).isEqualTo("now-present");
        assertThat(loads.get()).isEqualTo(2);
    }
}
