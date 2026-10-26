package io.github.ryancamus.clinic.event.service;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 发件箱监控：待投递积压、死信数量、最早一条待投递的等待时长。
 * 死信意味着下游处理连续失败，需要人工排查。
 */
@Component
public class OutboxMetrics implements MeterBinder {

    private final JdbcTemplate jdbc;

    public OutboxMetrics(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        Gauge.builder("clinic.outbox.pending", () -> count("SELECT COUNT(*) FROM outbox_message WHERE status = 0"))
                .description("待投递的事件数").register(registry);
        Gauge.builder("clinic.outbox.dead", () -> count("SELECT COUNT(*) FROM outbox_message WHERE status = 2"))
                .description("投递失败转为死信的事件数").register(registry);
        Gauge.builder("clinic.outbox.oldest.pending.seconds", () -> count(
                        "SELECT COALESCE(TIMESTAMPDIFF(SECOND, MIN(create_time), NOW()), 0) FROM outbox_message WHERE status = 0"))
                .description("最早一条待投递事件已等待的秒数").register(registry);
    }

    private double count(String sql) {
        Number n = jdbc.queryForObject(sql, Number.class);
        return n == null ? 0 : n.doubleValue();
    }
}
