package io.github.ryancamus.clinic.finance.service;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** 对账监控：未处理的渠道差异（长款、短款、金额不符） */
@Component
public class ReconMetrics implements MeterBinder {

    private final JdbcTemplate jdbc;

    public ReconMetrics(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        Gauge.builder("clinic.recon.open.diffs", () -> {
                    Number n = jdbc.queryForObject("SELECT COUNT(*) FROM recon_diff WHERE status = 0", Number.class);
                    return n == null ? 0 : n.doubleValue();
                })
                .description("未处理的对账差异数").register(registry);
    }
}
