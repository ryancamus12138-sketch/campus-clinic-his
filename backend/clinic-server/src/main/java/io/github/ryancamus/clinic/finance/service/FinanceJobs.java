package io.github.ryancamus.clinic.finance.service;

import lombok.RequiredArgsConstructor;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;

/**
 * 财务定时任务（校医院时区）。
 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "clinic.jobs", name = "enabled", havingValue = "true", matchIfMissing = true)
public class FinanceJobs {

    private final ReconService reconService;
    private final ReportService reportService;
    private final Clock clock;

    /** T+1 渠道对账：渠道账单一般在次日凌晨生成 */
    @Scheduled(cron = "0 0 2 * * *", zone = "${clinic.timezone:Asia/Shanghai}")
    @SchedulerLock(name = "channel-recon", lockAtMostFor = "PT30M")
    public void reconcileYesterday() {
        reconService.reconcile(LocalDate.now(clock).minusDays(1));
    }

    /** 报表校准：用资金流水重算前两天的预聚合，兜底修正预聚合与流水的任何不一致 */
    @Scheduled(cron = "0 0 3 * * *", zone = "${clinic.timezone:Asia/Shanghai}")
    @SchedulerLock(name = "report-rebuild", lockAtMostFor = "PT30M")
    public void rebuildReports() {
        LocalDate today = LocalDate.now(clock);
        reportService.rebuildDay(today.minusDays(2));
        reportService.rebuildDay(today.minusDays(1));
    }
}
