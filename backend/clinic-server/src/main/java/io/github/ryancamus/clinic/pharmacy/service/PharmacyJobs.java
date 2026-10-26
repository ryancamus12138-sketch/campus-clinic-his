package io.github.ryancamus.clinic.pharmacy.service;

import lombok.RequiredArgsConstructor;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "clinic.jobs", name = "enabled", havingValue = "true", matchIfMissing = true)
public class PharmacyJobs {

    private final TraceUploadService traceUploadService;

    @Scheduled(fixedDelayString = "PT60S", initialDelayString = "PT50S")
    @SchedulerLock(name = "trace-upload", lockAtMostFor = "PT5M")
    public void uploadTraceCodes() {
        while (traceUploadService.uploadPending(200) == 200) {
            Thread.onSpinWait();
        }
    }
}
