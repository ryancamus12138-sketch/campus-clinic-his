package io.github.ryancamus.clinic.event.service;

import lombok.RequiredArgsConstructor;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDateTime;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "clinic.jobs", name = "enabled", havingValue = "true", matchIfMissing = true)
public class OutboxJobs {

    private static final int BATCH = 100;

    private final OutboxService outboxService;
    private final Clock clock;

    @Scheduled(fixedDelayString = "PT1S", initialDelayString = "PT15S")
    @SchedulerLock(name = "outbox-dispatch", lockAtMostFor = "PT1M")
    public void dispatch() {
        while (outboxService.dispatchOnce(LocalDateTime.now(clock), BATCH) == BATCH) {
            Thread.onSpinWait();
        }
    }
}
