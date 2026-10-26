package io.github.ryancamus.clinic.schedule.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.github.ryancamus.clinic.charge.api.PriceQueryApi;
import io.github.ryancamus.clinic.common.exception.BizException;
import io.github.ryancamus.clinic.common.result.PageResult;
import io.github.ryancamus.clinic.framework.cache.LocalCache;
import io.github.ryancamus.clinic.framework.security.SecurityUtils;
import io.github.ryancamus.clinic.schedule.domain.dto.SlotBatchCreateDTO;
import io.github.ryancamus.clinic.schedule.domain.entity.ScheduleSlot;
import io.github.ryancamus.clinic.schedule.domain.query.AvailableSlotQuery;
import io.github.ryancamus.clinic.schedule.domain.query.SlotQuery;
import io.github.ryancamus.clinic.schedule.domain.vo.SlotVO;
import io.github.ryancamus.clinic.schedule.enums.ScheduleErrorCode;
import io.github.ryancamus.clinic.schedule.enums.SlotType;
import io.github.ryancamus.clinic.schedule.mapper.ScheduleSlotMapper;
import io.github.ryancamus.clinic.system.api.OrgQueryApi;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 号源：排班放号、收费窗口查询可挂号时段。
 * <p>
 * 多个窗口同时挂号（如疫苗、体检集中放号）时，号源的静态信息（日期、时段、价目）走 5 秒本地缓存，
 * 列表查询走 2 秒本地缓存，剩余量读 Redis 实时值，数据库只承担真正的扣减。
 */
@Service
@RequiredArgsConstructor
public class SlotService {

    private static final int MAX_SLOTS_PER_BATCH = 2_000;

    private final ScheduleSlotMapper slotMapper;
    private final SlotStockCache stockCache;
    private final PriceQueryApi priceQueryApi;
    private final OrgQueryApi orgQueryApi;
    private final Clock clock;

    private final SlotWarmer slotWarmer;

    private final LocalCache<Long, ScheduleSlot> slotMetaCache = LocalCache.of(20_000, Duration.ofSeconds(5));
    private final LocalCache<AvailableSlotQuery, List<ScheduleSlot>> availableCache =
            LocalCache.of(1_000, Duration.ofSeconds(2));

    public int batchCreate(SlotBatchCreateDTO dto) {
        BizException.throwIf(!SlotType.isValid(dto.slotType()), ScheduleErrorCode.INVALID_PERIOD, "号源类型不正确");
        BizException.throwIf(!orgQueryApi.deptExists(dto.campusId(), dto.deptId()), ScheduleErrorCode.DEPT_NOT_FOUND);
        LocalDateTime now = LocalDateTime.now(clock);
        BizException.throwIf(priceQueryApi.findEffective(dto.priceItemCode(), now).isEmpty(),
                ScheduleErrorCode.PRICE_ITEM_NOT_FOUND);
        dto.periods().forEach(p -> BizException.throwIf(!p.endTime().isAfter(p.startTime()),
                ScheduleErrorCode.INVALID_PERIOD, "结束时间必须晚于开始时间"));
        BizException.throwIf(dto.dates().size() * dto.periods().size() > MAX_SLOTS_PER_BATCH,
                ScheduleErrorCode.INVALID_PERIOD, "单次最多生成 " + MAX_SLOTS_PER_BATCH + " 个时段");

        Long operator = SecurityUtils.currentUserIdOrNull();
        long operatorId = operator == null ? 0L : operator;
        // MySQL DATETIME 会把毫秒四舍五入到秒：不截断的话，“立即开放”的号源可能被存成半秒后才开放
        LocalDateTime openTime = (dto.openTime() == null ? now : dto.openTime()).truncatedTo(ChronoUnit.SECONDS);
        List<ScheduleSlot> slots = new ArrayList<>();
        for (LocalDate date : dto.dates()) {
            for (SlotBatchCreateDTO.Period period : dto.periods()) {
                ScheduleSlot slot = new ScheduleSlot();
                slot.setId(IdWorker.getId());
                slot.setCampusId(dto.campusId());
                slot.setDeptId(dto.deptId());
                slot.setDoctorId(dto.doctorId() == null ? 0L : dto.doctorId());
                slot.setSlotType(dto.slotType());
                slot.setSlotDate(date);
                slot.setStartTime(period.startTime());
                slot.setEndTime(period.endTime());
                slot.setTotal(period.total());
                slot.setRemaining(period.total());
                slot.setPriceItemCode(dto.priceItemCode());
                slot.setStatus(ScheduleSlot.STATUS_OPEN);
                slot.setOpenTime(openTime);
                slot.setCreateTime(now);
                slot.setUpdateTime(now);
                slot.setCreateBy(operatorId);
                slot.setUpdateBy(operatorId);
                slots.add(slot);
            }
        }
        int inserted = slotMapper.insertIgnoreBatch(slots);
        // 立即开放或 5 分钟内开放的号源马上装入 Redis，避免放号瞬间的缓存击穿
        if (!openTime.isAfter(now.plusMinutes(5))) {
            slots.forEach(s -> slotWarmer.warm(s.getId()));
        }
        return inserted;
    }

    public void changeStatus(Long slotId, int status) {
        ScheduleSlot slot = Optional.ofNullable(slotMapper.selectById(slotId))
                .orElseThrow(() -> new BizException(ScheduleErrorCode.SLOT_NOT_FOUND));
        slotMapper.update(Wrappers.<ScheduleSlot>lambdaUpdate()
                .set(ScheduleSlot::getStatus, status)
                .set(ScheduleSlot::getUpdateTime, LocalDateTime.now(clock))
                .eq(ScheduleSlot::getId, slot.getId()));
        slotMetaCache.invalidate(slotId);
        availableCache.invalidateAll();
        if (status != ScheduleSlot.STATUS_OPEN) {
            // 关闭后不再允许通过 Redis 闸门；重新开放时按数据库重新预热
            stockCache.evict(slotId);
        }
    }

    public PageResult<SlotVO> page(SlotQuery query) {
        LambdaQueryWrapper<ScheduleSlot> wrapper = Wrappers.<ScheduleSlot>lambdaQuery()
                .eq(query.campusId() != null, ScheduleSlot::getCampusId, query.campusId())
                .eq(query.deptId() != null, ScheduleSlot::getDeptId, query.deptId())
                .eq(query.slotType() != null, ScheduleSlot::getSlotType, query.slotType())
                .ge(query.dateFrom() != null, ScheduleSlot::getSlotDate, query.dateFrom())
                .le(query.dateTo() != null, ScheduleSlot::getSlotDate, query.dateTo())
                .orderByAsc(ScheduleSlot::getSlotDate, ScheduleSlot::getStartTime, ScheduleSlot::getDeptId);
        Page<ScheduleSlot> page = slotMapper.selectPage(Page.of(query.pageOrDefault(), query.sizeOrDefault()), wrapper);
        return new PageResult<>(toVOs(page.getRecords()), page.getTotal(), page.getCurrent(), page.getSize());
    }

    /** 窗口挂号：指定日期、类型下已开放且尚未结束的时段 */
    public List<SlotVO> listAvailable(AvailableSlotQuery query) {
        LocalDateTime now = LocalDateTime.now(clock);
        List<ScheduleSlot> slots = availableCache.get(query, q -> slotMapper.selectList(
                Wrappers.<ScheduleSlot>lambdaQuery()
                        .eq(ScheduleSlot::getSlotType, q.slotType())
                        .eq(ScheduleSlot::getSlotDate, q.date())
                        .eq(q.campusId() != null, ScheduleSlot::getCampusId, q.campusId())
                        .eq(q.deptId() != null, ScheduleSlot::getDeptId, q.deptId())
                        .eq(ScheduleSlot::getStatus, ScheduleSlot.STATUS_OPEN)
                        .orderByAsc(ScheduleSlot::getStartTime, ScheduleSlot::getDeptId)));
        return toVOs(slots.stream()
                .filter(s -> !s.getOpenTime().isAfter(now) && s.endAt().isAfter(now))
                .toList());
    }

    /** 号源静态信息（短时缓存）；剩余量不可信，扣减以数据库为准 */
    public Optional<ScheduleSlot> getSlotMeta(Long slotId) {
        return Optional.ofNullable(slotMetaCache.get(slotId, slotMapper::selectById));
    }

    private List<SlotVO> toVOs(List<ScheduleSlot> slots) {
        if (slots.isEmpty()) {
            return List.of();
        }
        Map<Long, String> deptNames = orgQueryApi.deptNames(slots.stream().map(ScheduleSlot::getDeptId).toList());
        Map<Long, String> doctorNames = orgQueryApi.userNicknames(slots.stream().map(ScheduleSlot::getDoctorId).toList());
        Map<String, PriceQueryApi.PriceSnapshot> prices = priceQueryApi.findCurrent(
                slots.stream().map(ScheduleSlot::getPriceItemCode).distinct().toList());
        List<Integer> liveRemaining = stockCache.peekRemaining(slots.stream().map(ScheduleSlot::getId).toList());
        List<SlotVO> result = new ArrayList<>(slots.size());
        for (int i = 0; i < slots.size(); i++) {
            ScheduleSlot s = slots.get(i);
            Integer live = liveRemaining.get(i);
            PriceQueryApi.PriceSnapshot price = prices.get(s.getPriceItemCode());
            result.add(new SlotVO(s.getId(), s.getCampusId(), s.getDeptId(), deptNames.get(s.getDeptId()),
                    s.getDoctorId(), doctorNames.get(s.getDoctorId()), s.getSlotType(), s.getSlotDate(),
                    s.getStartTime(), s.getEndTime(), s.getTotal(), live != null ? live : s.getRemaining(),
                    s.getPriceItemCode(), price == null ? null : price.unitPrice(), s.getStatus(), s.getOpenTime()));
        }
        return result;
    }
}
