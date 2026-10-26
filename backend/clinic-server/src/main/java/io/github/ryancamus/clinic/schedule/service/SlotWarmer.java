package io.github.ryancamus.clinic.schedule.service;

import io.github.ryancamus.clinic.framework.cache.SingleFlight;
import io.github.ryancamus.clinic.schedule.domain.entity.ScheduleSlot;
import io.github.ryancamus.clinic.schedule.mapper.RegistrationMapper;
import io.github.ryancamus.clinic.schedule.mapper.ScheduleSlotMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;

/**
 * 以数据库为准把号源装入 Redis。
 * <p>
 * 号源未预热时，放号瞬间的大量请求会同时发现 Redis 里没有数据、同时去数据库加载，占满连接池。
 * 因此同一号源同一时刻只有一个线程加载（单飞），其他请求等待其结果；放号前 5 分钟由定时任务提前预热，
 * 立即开放的号源在排班时就预热。
 */
@Component
@RequiredArgsConstructor
public class SlotWarmer {

    private final ScheduleSlotMapper slotMapper;
    private final RegistrationMapper registrationMapper;
    private final SlotStockCache stockCache;
    private final Clock clock;
    private final SingleFlight<Long, Boolean> singleFlight = new SingleFlight<>();

    public void warm(Long slotId) {
        singleFlight.load(slotId, id -> {
            if (stockCache.peekRemaining(id) != null) {
                return true;
            }
            ScheduleSlot slot = slotMapper.selectById(id);
            if (slot == null || slot.getStatus() != ScheduleSlot.STATUS_OPEN) {
                return false;
            }
            return stockCache.warm(id, slot.getRemaining(), registrationMapper.selectActivePatientIds(id), ttl(slot));
        });
    }

    public void rebuild(ScheduleSlot slot) {
        stockCache.rebuild(slot.getId(), slot.getRemaining(),
                registrationMapper.selectActivePatientIds(slot.getId()), ttl(slot));
    }

    /** 缓存保留到号源结束后一天 */
    Duration ttl(ScheduleSlot slot) {
        Duration untilEnd = Duration.between(LocalDateTime.now(clock), slot.endAt());
        return untilEnd.isNegative() ? Duration.ofHours(1) : untilEnd.plusDays(1);
    }
}
