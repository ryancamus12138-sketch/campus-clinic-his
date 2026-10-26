package io.github.ryancamus.clinic.schedule;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import io.github.ryancamus.clinic.common.exception.BizException;
import io.github.ryancamus.clinic.schedule.domain.entity.Registration;
import io.github.ryancamus.clinic.schedule.domain.entity.ScheduleSlot;
import io.github.ryancamus.clinic.schedule.enums.ScheduleErrorCode;
import io.github.ryancamus.clinic.schedule.enums.SlotType;
import io.github.ryancamus.clinic.schedule.mapper.RegistrationMapper;
import io.github.ryancamus.clinic.schedule.service.BookingService;
import io.github.ryancamus.clinic.schedule.service.SlotStockReconciler;
import io.github.ryancamus.clinic.schedule.service.SlotWarmer;
import io.github.ryancamus.clinic.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 并发正确性：疫苗定时放号的抢约场景。
 * <p>
 * 断言的是“结果不变量”（成功数 = 号源数、余量为 0、无重复），而不是耗时；
 * 耗时与吞吐由 perf/ 下的 k6 压测在限定资源的环境中测量。
 */
@IntegrationTest
class BookingConcurrencyTest {

    private static final int SLOTS = 50;
    private static final int PATIENTS = 1_000;
    private static final int DUPLICATE_ATTEMPTS = 200;

    @Autowired
    private BookingService bookingService;
    @Autowired
    private SlotWarmer slotWarmer;
    @Autowired
    private SlotFixtures slots;
    @Autowired
    private RegistrationMapper registrationMapper;
    @Autowired
    private StringRedisTemplate redis;
    @Autowired
    private SlotStockReconciler reconciler;

    @Test
    void thousandPatientsRacingForFiftySlotsNeverOversell() throws Exception {
        ScheduleSlot slot = slots.create(SlotType.VACCINE.code(), SLOTS, "YM-FW");
        Outcome outcome = race(slot.getId(), 1_000_000L);

        assertInvariants(slot, outcome);
        assertThat(redis.opsForValue().get(stockKey(slot.getId()))).isEqualTo("0");
        assertThat(outcome.errors.get(ScheduleErrorCode.SLOT_FULL.code())).isNotNull();
        // 同一患者重复点击的请求全部被拦截为“重复预约”或“已约满”，没有一个成功
    }

    @Test
    void databaseStillPreventsOversellWhenRedisLosesItsState() throws Exception {
        // 对账只检查近两周的号源，所以这里用明天的时段
        ScheduleSlot slot = slots.createTomorrow(SlotType.VACCINE.code(), SLOTS, "YM-FW");
        slotWarmer.warm(slot.getId());
        // 模拟 Redis 主从切换丢数据：余量被错误地放大到 10 倍
        redis.opsForValue().set(stockKey(slot.getId()), String.valueOf(SLOTS * 10));

        Outcome outcome = race(slot.getId(), 2_000_000L);

        assertInvariants(slot, outcome);
        // Redis 仍是错误值，由对账任务连续两轮确认后按数据库修正
        assertThat(reconciler.reconcileOnce()).isZero();
        assertThat(reconciler.reconcileOnce()).isGreaterThanOrEqualTo(1);
        assertThat(redis.opsForValue().get(stockKey(slot.getId()))).isEqualTo("0");
        assertThat(redis.opsForSet().size("slot:{" + slot.getId() + "}:users")).isEqualTo(SLOTS);
    }

    private Outcome race(Long slotId, long patientIdBase) throws Exception {
        Outcome outcome = new Outcome();
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < PATIENTS + DUPLICATE_ATTEMPTS; i++) {
                // 前 200 个患者各多发一次请求，模拟重复点击
                long patientId = patientIdBase + (i < PATIENTS ? i : i - PATIENTS);
                futures.add(pool.submit(() -> {
                    start.await();
                    try {
                        bookingService.book(patientId, slotId);
                        outcome.success.incrementAndGet();
                    } catch (BizException e) {
                        outcome.errors.merge(e.getErrorCode().code(), 1, Integer::sum);
                    }
                    return null;
                }));
            }
            long begin = System.nanoTime();
            start.countDown();
            for (Future<?> f : futures) {
                f.get(60, TimeUnit.SECONDS);
            }
            outcome.elapsedMillis = (System.nanoTime() - begin) / 1_000_000;
        }
        System.out.printf("[concurrency] slot=%d requests=%d success=%d errors=%s elapsed=%dms%n",
                slotId, PATIENTS + DUPLICATE_ATTEMPTS, outcome.success.get(), outcome.errors, outcome.elapsedMillis);
        return outcome;
    }

    private void assertInvariants(ScheduleSlot slot, Outcome outcome) {
        assertThat(outcome.success.get()).as("成功预约数必须恰好等于号源数").isEqualTo(SLOTS);
        assertThat(slots.reload(slot.getId()).getRemaining()).as("数据库余量").isZero();
        List<Registration> regs = registrationMapper.selectList(Wrappers.<Registration>lambdaQuery()
                .eq(Registration::getSlotId, slot.getId()));
        assertThat(regs).hasSize(SLOTS);
        assertThat(regs.stream().map(Registration::getPatientId).distinct().count())
                .as("同一患者不会占用两个名额").isEqualTo(SLOTS);
        assertThat(outcome.errors.keySet()).as("失败只能是约满或重复预约")
                .isSubsetOf(ScheduleErrorCode.SLOT_FULL.code(), ScheduleErrorCode.ALREADY_BOOKED.code());
    }

    private static String stockKey(Long slotId) {
        return "slot:{" + slotId + "}:stock";
    }

    private static final class Outcome {
        final AtomicInteger success = new AtomicInteger();
        final Map<Integer, Integer> errors = new ConcurrentHashMap<>();
        long elapsedMillis;
    }
}
