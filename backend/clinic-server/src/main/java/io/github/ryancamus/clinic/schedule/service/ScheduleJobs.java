package io.github.ryancamus.clinic.schedule.service;

import lombok.RequiredArgsConstructor;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 预约相关的定时任务。多实例部署时由 ShedLock 保证同一任务只在一个实例上执行。
 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "clinic.jobs", name = "enabled", havingValue = "true", matchIfMissing = true)
public class ScheduleJobs {

    private static final int EXPIRE_BATCH = 500;

    private final BookingService bookingService;
    private final SlotStockReconciler reconciler;

    /** 超时未支付的预约释放号源 */
    @Scheduled(fixedDelayString = "PT30S", initialDelayString = "PT20S")
    @SchedulerLock(name = "booking-expire", lockAtMostFor = "PT2M")
    public void expireOverdueBookings() {
        // 积压较多时连续处理，直到一批不满为止
        while (bookingService.expireOverdue(EXPIRE_BATCH) == EXPIRE_BATCH) {
            Thread.onSpinWait();
        }
    }

    @Scheduled(fixedDelayString = "PT30S", initialDelayString = "PT10S")
    @SchedulerLock(name = "slot-prewarm", lockAtMostFor = "PT1M")
    public void prewarmSlots() {
        reconciler.prewarmUpcoming();
    }

    @Scheduled(fixedDelayString = "PT60S", initialDelayString = "PT60S")
    @SchedulerLock(name = "slot-reconcile", lockAtMostFor = "PT2M")
    public void reconcileSlotStock() {
        reconciler.reconcileOnce();
    }
}
