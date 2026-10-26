package io.github.ryancamus.clinic.support;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * 可拨动的业务时钟：跨日、跨班次等场景在测试中“穿越”到指定时间，测试结束务必 reset。
 */
public class MutableClock extends Clock {

    private final ZoneId zone;
    private volatile Duration offset = Duration.ZERO;

    public MutableClock(ZoneId zone) {
        this.zone = zone;
    }

    public void travelTo(LocalDateTime target) {
        offset = Duration.between(Instant.now(), target.atZone(zone).toInstant());
    }

    public void reset() {
        offset = Duration.ZERO;
    }

    @Override
    public ZoneId getZone() {
        return zone;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        MutableClock c = new MutableClock(zone);
        c.offset = offset;
        return c;
    }

    @Override
    public Instant instant() {
        return Instant.now().plus(offset);
    }
}
