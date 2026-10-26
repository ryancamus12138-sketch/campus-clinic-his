package io.github.ryancamus.clinic.schedule.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import io.github.ryancamus.clinic.schedule.domain.entity.ScheduleSlot;
import io.github.ryancamus.clinic.schedule.mapper.RegistrationMapper;
import io.github.ryancamus.clinic.schedule.mapper.ScheduleSlotMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Redis 与数据库的号源对账，以数据库为准。
 * <p>
 * 正常情况下两边一致；只有进程在“数据库已提交、Redis 未归还”之间崩溃，或 Redis 主从切换丢数据时才会偏离。
 * 因为预约请求在途时两边本来就会短暂不一致，所以要求<b>连续两轮</b>看到同一偏差才修正，避免误伤在途请求。
 * 修正方向即使判断错误也不会超卖：数据库的条件更新始终是最后一道防线。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SlotStockReconciler {

    private static final int MAX_SLOTS_PER_ROUND = 500;
    private static final int LOOKAHEAD_DAYS = 14;

    private final ScheduleSlotMapper slotMapper;
    private final RegistrationMapper registrationMapper;
    private final SlotStockCache stockCache;
    private final SlotWarmer slotWarmer;
    private final Clock clock;

    /** slotId → 上一轮看到的偏差签名 */
    private final Map<Long, String> suspected = new ConcurrentHashMap<>();

    /** @return 本轮修正的号源数 */
    public int reconcileOnce() {
        LocalDateTime now = LocalDateTime.now(clock);
        List<ScheduleSlot> slots = activeSlots(now);
        List<Integer> cached = stockCache.peekRemaining(slots.stream().map(ScheduleSlot::getId).toList());
        int fixed = 0;
        for (int i = 0; i < slots.size(); i++) {
            ScheduleSlot slot = slots.get(i);
            Integer redisRemaining = cached.get(i);
            if (redisRemaining == null) {
                suspected.remove(slot.getId());
                continue;
            }
            int occupying = registrationMapper.countOccupying(slot.getId());
            boolean consistent = redisRemaining.equals(slot.getRemaining())
                    && slot.getRemaining() + occupying == slot.getTotal();
            if (consistent) {
                suspected.remove(slot.getId());
                continue;
            }
            String signature = redisRemaining + "/" + slot.getRemaining() + "/" + occupying;
            if (signature.equals(suspected.get(slot.getId()))) {
                slotWarmer.rebuild(slot);
                suspected.remove(slot.getId());
                fixed++;
                log.warn("号源 {} 的 Redis 余量 {} 与数据库 {} 不一致（占用 {}），已按数据库重建",
                        slot.getId(), redisRemaining, slot.getRemaining(), occupying);
            } else {
                suspected.put(slot.getId(), signature);
            }
        }
        return fixed;
    }

    /** 预热即将放号的号源，避免放号瞬间大量请求同时去数据库加载 */
    public int prewarmUpcoming() {
        LocalDateTime now = LocalDateTime.now(clock);
        List<ScheduleSlot> upcoming = slotMapper.selectList(Wrappers.<ScheduleSlot>lambdaQuery()
                .select(ScheduleSlot::getId)
                .eq(ScheduleSlot::getStatus, ScheduleSlot.STATUS_OPEN)
                .between(ScheduleSlot::getOpenTime, now.minusMinutes(1), now.plusMinutes(5))
                .last("LIMIT " + MAX_SLOTS_PER_ROUND));
        upcoming.forEach(s -> slotWarmer.warm(s.getId()));
        return upcoming.size();
    }

    private List<ScheduleSlot> activeSlots(LocalDateTime now) {
        LocalDate today = now.toLocalDate();
        return slotMapper.selectList(Wrappers.<ScheduleSlot>lambdaQuery()
                .eq(ScheduleSlot::getStatus, ScheduleSlot.STATUS_OPEN)
                .le(ScheduleSlot::getOpenTime, now)
                .between(ScheduleSlot::getSlotDate, today, today.plusDays(LOOKAHEAD_DAYS))
                .orderByAsc(ScheduleSlot::getSlotDate)
                .last("LIMIT " + MAX_SLOTS_PER_ROUND));
    }
}
