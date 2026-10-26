package io.github.ryancamus.clinic.framework.time;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * 真实时钟，时区与业务时钟相同（clinic.timezone）。
 * <p>
 * 用于审计日志时间、口令修改时间等必须反映真实发生时刻的字段：测试会把业务时钟 {@link Clock} 拨到未来或过去，
 * 这些字段不应跟着变化。
 */
@Component
public class WallClock {

    private final Clock clock;

    public WallClock(@Value("${clinic.timezone:Asia/Shanghai}") String zone) {
        this.clock = Clock.system(ZoneId.of(zone));
    }

    public LocalDateTime now() {
        return LocalDateTime.now(clock);
    }
}
