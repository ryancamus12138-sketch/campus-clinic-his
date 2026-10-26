package io.github.ryancamus.clinic.schedule;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import io.github.ryancamus.clinic.schedule.domain.dto.SlotBatchCreateDTO;
import io.github.ryancamus.clinic.schedule.domain.entity.ScheduleSlot;
import io.github.ryancamus.clinic.schedule.mapper.ScheduleSlotMapper;
import io.github.ryancamus.clinic.schedule.service.SlotService;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 为每个测试生成互不冲突的号源（日期递增），测试之间不共享号源状态。
 */
public class SlotFixtures {

    private static final AtomicInteger DAY_OFFSET = new AtomicInteger(30);
    private static final AtomicInteger MINUTE_OFFSET = new AtomicInteger();

    private final SlotService slotService;
    private final ScheduleSlotMapper slotMapper;

    public SlotFixtures(SlotService slotService, ScheduleSlotMapper slotMapper) {
        this.slotService = slotService;
        this.slotMapper = slotMapper;
    }

    public ScheduleSlot create(int slotType, int total, String priceItemCode) {
        return create(slotType, total, priceItemCode, null);
    }

    public ScheduleSlot create(int slotType, int total, String priceItemCode, LocalDateTime openTime) {
        LocalDate date = LocalDate.now().plusDays(DAY_OFFSET.getAndIncrement());
        return createAt(date, LocalTime.of(9, 0), slotType, total, priceItemCode, openTime);
    }

    /** 明天（校医院时区）的号源，开始时间各不相同；用于需要落在对账窗口内的测试 */
    public ScheduleSlot createTomorrow(int slotType, int total, String priceItemCode) {
        LocalDate tomorrow = LocalDate.now(ZoneId.of("Asia/Shanghai")).plusDays(1);
        LocalTime start = LocalTime.of(6, 0).plusMinutes(5L * MINUTE_OFFSET.getAndIncrement());
        return createAt(tomorrow, start, slotType, total, priceItemCode, null);
    }

    private ScheduleSlot createAt(LocalDate date, LocalTime start, int slotType, int total, String priceItemCode,
                                  LocalDateTime openTime) {
        slotService.batchCreate(new SlotBatchCreateDTO(1L, 101L, 0L, slotType, List.of(date),
                List.of(new SlotBatchCreateDTO.Period(start, start.plusMinutes(30), total)), priceItemCode, openTime));
        return slotMapper.selectOne(Wrappers.<ScheduleSlot>lambdaQuery()
                .eq(ScheduleSlot::getSlotDate, date)
                .eq(ScheduleSlot::getSlotType, slotType)
                .eq(ScheduleSlot::getDeptId, 101L)
                .eq(ScheduleSlot::getStartTime, start));
    }

    public ScheduleSlot reload(Long slotId) {
        return slotMapper.selectById(slotId);
    }
}
