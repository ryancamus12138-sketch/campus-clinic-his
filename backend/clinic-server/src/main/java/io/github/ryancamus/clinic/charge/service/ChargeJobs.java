package io.github.ryancamus.clinic.charge.service;

import lombok.RequiredArgsConstructor;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 收费相关定时任务，多实例下由 ShedLock 保证单实例执行。
 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "clinic.jobs", name = "enabled", havingValue = "true", matchIfMissing = true)
public class ChargeJobs {

    private static final int BATCH = 200;

    private final ChargeOrderService chargeOrderService;
    private final RefundService refundService;
    private final CounterCheckoutService counterCheckoutService;

    /** 渠道退款失败或结果未知的退费单重试 */
    @Scheduled(fixedDelayString = "PT60S", initialDelayString = "PT45S")
    @SchedulerLock(name = "refund-retry", lockAtMostFor = "PT5M")
    public void retryRefunds() {
        refundService.retry(BATCH);
    }

    /** 收款途中程序中断的窗口结算：转为待人工处理并释放锁 */
    @Scheduled(fixedDelayString = "PT60S", initialDelayString = "PT50S")
    @SchedulerLock(name = "checkout-stale", lockAtMostFor = "PT2M")
    public void markStaleCheckouts() {
        counterCheckoutService.markStale(Duration.ofMinutes(5), BATCH);
    }

    @Scheduled(fixedDelayString = "PT60S", initialDelayString = "PT40S")
    @SchedulerLock(name = "charge-expire", lockAtMostFor = "PT2M")
    public void expireChargeOrders() {
        chargeOrderService.expireOverdue(BATCH);
    }
}
