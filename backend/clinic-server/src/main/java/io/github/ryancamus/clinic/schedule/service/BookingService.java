package io.github.ryancamus.clinic.schedule.service;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import io.github.ryancamus.clinic.charge.api.ChargeApi.ChargeOrderView;
import io.github.ryancamus.clinic.charge.api.ChargeApi.CloseOutcome;
import io.github.ryancamus.clinic.charge.api.ChargeApi;
import io.github.ryancamus.clinic.charge.api.PriceQueryApi;
import io.github.ryancamus.clinic.common.exception.BizException;
import io.github.ryancamus.clinic.common.money.Money;
import io.github.ryancamus.clinic.framework.security.SecurityUtils;
import io.github.ryancamus.clinic.framework.util.BizNoGenerator;
import io.github.ryancamus.clinic.schedule.config.BookingProperties;
import io.github.ryancamus.clinic.schedule.domain.entity.Registration;
import io.github.ryancamus.clinic.schedule.domain.entity.ScheduleSlot;
import io.github.ryancamus.clinic.schedule.domain.vo.BookingVO;
import io.github.ryancamus.clinic.schedule.domain.vo.RegistrationResultVO;
import io.github.ryancamus.clinic.schedule.enums.RegistrationStatus;
import io.github.ryancamus.clinic.schedule.enums.ScheduleErrorCode;
import io.github.ryancamus.clinic.schedule.enums.SlotType;
import io.github.ryancamus.clinic.schedule.mapper.RegistrationMapper;
import io.github.ryancamus.clinic.schedule.mapper.ScheduleSlotMapper;
import io.github.ryancamus.clinic.system.api.OrgQueryApi;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 窗口挂号（门诊、新生体检、疫苗共用）：收费员为患者选择号源并收取挂号费。
 * <p>
 * 多个窗口、多个校区同时挂同一个号源时不会超挂：Redis Lua 原子预扣（{@link SlotStockCache}）→ 数据库条件更新 + 唯一索引。
 * Redis 预扣成功后若数据库失败，在 finally 中归还 Redis，保证两边最终一致；
 * 遗留的不一致由对账任务以数据库为准修正。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BookingService {

    private static final String REG_NO_PREFIX = "R";
    private static final int MY_BOOKINGS_LIMIT = 50;

    private final ScheduleSlotMapper slotMapper;
    private final RegistrationMapper registrationMapper;
    private final SlotService slotService;
    private final SlotStockCache stockCache;
    private final SlotWarmer slotWarmer;
    private final BookingWriter bookingWriter;
    private final PriceQueryApi priceQueryApi;
    private final ChargeApi chargeApi;
    private final OrgQueryApi orgQueryApi;
    private final BookingProperties properties;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;

    /**
     * 窗口挂号：收费员为患者选择号源，需缴费的同时生成挂号费收费单，随后在窗口收费。
     */
    public RegistrationResultVO register(Long patientId, Long slotId) {
        BookingVO booking = book(patientId, slotId);
        ChargeOrderView charge = booking.status() == RegistrationStatus.PENDING_PAYMENT
                ? prepareCharge(booking.regNo()) : null;
        return new RegistrationResultVO(booking, charge);
    }

    public BookingVO book(Long patientId, Long slotId) {
        LocalDateTime now = LocalDateTime.now(clock);
        ScheduleSlot slot = slotService.getSlotMeta(slotId)
                .orElseThrow(() -> new BizException(ScheduleErrorCode.SLOT_NOT_FOUND));
        checkBookable(slot, now);
        PriceQueryApi.PriceSnapshot price = priceQueryApi.findCurrentCached(slot.getPriceItemCode())
                .orElseThrow(() -> new BizException(ScheduleErrorCode.PRICE_ITEM_NOT_FOUND));

        long gate = stockCache.tryAcquire(slotId, patientId);
        if (gate == SlotStockCache.NOT_WARMED) {
            slotWarmer.warm(slotId);
            gate = stockCache.tryAcquire(slotId, patientId);
        }
        BizException.throwIf(gate == SlotStockCache.DUPLICATE, ScheduleErrorCode.ALREADY_BOOKED);
        BizException.throwIf(gate == SlotStockCache.SOLD_OUT, ScheduleErrorCode.SLOT_FULL);
        // NOT_WARMED 仍可能出现（例如 Redis 异常后键被清除），此时直接由数据库裁决
        boolean acquiredInRedis = gate >= 0;

        boolean committed = false;
        boolean duplicateInDb = false;
        try {
            // 合并写入：成功请求批量插入并一次性扣减号源，见 BookingWriter
            Registration registration = newRegistration(patientId, slot, price.unitPrice(), now);
            bookingWriter.write(registration);
            committed = true;
            return toVO(registration, slot, price.unitPrice());
        } catch (DuplicateKeyException e) {
            duplicateInDb = true;
            throw new BizException(ScheduleErrorCode.ALREADY_BOOKED);
        } finally {
            if (acquiredInRedis && !committed) {
                if (duplicateInDb) {
                    stockCache.returnStock(slotId);
                } else {
                    stockCache.release(slotId, patientId);
                }
            }
        }
    }

    /**
     * 窗口退号（未缴费）。待支付可直接取消（先关闭收费单，关单时发现已付款则不能取消）；
     * 已预约且无需缴费（如学校统一付费的体检）可在开始前取消；已缴费的需要退费，走收费处退号流程。
     */
    public void cancel(String regNo) {
        Registration reg = findByRegNo(regNo);
        ScheduleSlot slot = slotService.getSlotMeta(reg.getSlotId())
                .orElseThrow(() -> new BizException(ScheduleErrorCode.SLOT_NOT_FOUND));
        int from = reg.getStatus();
        if (from == RegistrationStatus.BOOKED) {
            BizException.throwIf(reg.getChargeOrderId() != null, ScheduleErrorCode.PAID_BOOKING_CANCEL);
            BizException.throwIf(!slot.startAt().isAfter(LocalDateTime.now(clock)), ScheduleErrorCode.CANNOT_CANCEL);
        } else if (from != RegistrationStatus.PENDING_PAYMENT) {
            throw new BizException(ScheduleErrorCode.CANNOT_CANCEL);
        } else if (reg.getChargeOrderId() != null
                && chargeApi.closeIfUnpaid(reg.getChargeOrderId()) != CloseOutcome.CLOSED) {
            throw new BizException(ScheduleErrorCode.PAID_BOOKING_CANCEL);
        }
        BizException.throwIf(!releaseSlot(reg, from, RegistrationStatus.CANCELLED),
                ScheduleErrorCode.CANNOT_CANCEL, "预约状态已变化，请刷新后重试");
    }

    /**
     * 收费处退号：挂号费全额退回后取消预约并归还号源。已就诊的不处理（诊查已经发生）。
     */
    public void cancelAfterRefund(Long registrationId) {
        Registration reg = registrationMapper.selectById(registrationId);
        if (reg == null || reg.getStatus() != RegistrationStatus.BOOKED) {
            return;
        }
        releaseSlot(reg, RegistrationStatus.BOOKED, RegistrationStatus.CANCELLED);
    }

    /**
     * 为待支付的挂号生成挂号费收费单（幂等），收费员随后在窗口收费。
     */
    public ChargeOrderView prepareCharge(String regNo) {
        Registration reg = findByRegNo(regNo);
        if (reg.getChargeOrderId() != null) {
            return chargeApi.findView(reg.getChargeOrderId())
                    .orElseThrow(() -> new BizException(ScheduleErrorCode.NOT_PAYABLE));
        }
        BizException.throwIf(reg.getStatus() != RegistrationStatus.PENDING_PAYMENT, ScheduleErrorCode.NOT_PAYABLE);
        BizException.throwIf(!reg.getExpireTime().isAfter(LocalDateTime.now(clock)),
                ScheduleErrorCode.PAYMENT_DEADLINE_PASSED);
        ScheduleSlot slot = slotService.getSlotMeta(reg.getSlotId())
                .orElseThrow(() -> new BizException(ScheduleErrorCode.SLOT_NOT_FOUND));
        ChargeApi.RegistrationCharge command = new ChargeApi.RegistrationCharge(reg.getId(), reg.getPatientId(),
                chargeSourceOf(slot.getSlotType()), slot.getPriceItemCode(), reg.getCreateTime(), reg.getExpireTime(), slot.getDeptId());
        ChargeOrderView view;
        try {
            // 生成收费单与回写预约在同一事务：预约已被释放或已关联时整体回滚
            view = transactionTemplate.execute(tx -> {
                ChargeOrderView created = chargeApi.createForRegistration(command);
                if (registrationMapper.linkChargeOrder(reg.getId(), created.id()) == 0) {
                    tx.setRollbackOnly();
                    return null;
                }
                return created;
            });
        } catch (DuplicateKeyException e) {
            // 并发点击：另一个请求已为该预约生成收费单
            view = null;
        }
        if (view != null) {
            return view;
        }
        Registration current = registrationMapper.selectById(reg.getId());
        if (current.getChargeOrderId() != null) {
            return chargeApi.findView(current.getChargeOrderId())
                    .orElseThrow(() -> new BizException(ScheduleErrorCode.NOT_PAYABLE));
        }
        throw new BizException(ScheduleErrorCode.NOT_PAYABLE);
    }

    /**
     * 释放超时未支付的预约，由定时任务调用。每条单独一个事务：
     * 先 CAS 状态（与付款确认竞争，只有一方成功），成功后才归还号源。
     *
     * @return 本次释放的数量
     */
    public int expireOverdue(int limit) {
        List<Registration> overdue = registrationMapper.selectExpired(LocalDateTime.now(clock), limit);
        int released = 0;
        for (Registration reg : overdue) {
            // 已生成收费单的，先关单（关单时会向渠道确认是否其实已付款）；
            // 发现已付款则不释放，付款确认会把预约改为已预约
            if (reg.getChargeOrderId() != null
                    && chargeApi.closeIfUnpaid(reg.getChargeOrderId()) != CloseOutcome.CLOSED) {
                continue;
            }
            if (releaseSlot(reg, RegistrationStatus.PENDING_PAYMENT, RegistrationStatus.EXPIRED)) {
                released++;
            }
        }
        if (released > 0) {
            log.info("超时未支付预约已释放 {} 条", released);
        }
        return released;
    }

    /**
     * 把预约迁移到取消/过期并归还号源。状态和关联的收费单都必须与读取时一致，
     * 与付款确认、并发生成收费单竞争时只有一方成功。
     */
    private boolean releaseSlot(Registration reg, int from, int to) {
        boolean ok = Boolean.TRUE.equals(transactionTemplate.execute(tx -> {
            if (registrationMapper.casStatusWithCharge(reg.getId(), from, to, reg.getChargeOrderId()) == 0) {
                return false;
            }
            slotMapper.incrRemaining(reg.getSlotId());
            return true;
        }));
        if (ok) {
            stockCache.release(reg.getSlotId(), reg.getPatientId());
        }
        return ok;
    }

    /** 某患者最近的挂号记录（收费员查看） */
    public List<BookingVO> listByPatient(Long patientId) {
        List<Registration> regs = registrationMapper.selectList(Wrappers.<Registration>lambdaQuery()
                .eq(Registration::getPatientId, patientId)
                .orderByDesc(Registration::getId)
                .last("LIMIT " + MY_BOOKINGS_LIMIT));
        if (regs.isEmpty()) {
            return List.of();
        }
        Map<Long, ScheduleSlot> slots = slotMapper.selectByIds(regs.stream().map(Registration::getSlotId).toList())
                .stream().collect(Collectors.toMap(ScheduleSlot::getId, Function.identity()));
        Map<String, PriceQueryApi.PriceSnapshot> prices = priceQueryApi.findCurrent(
                slots.values().stream().map(ScheduleSlot::getPriceItemCode).distinct().toList());
        List<BookingVO> result = new ArrayList<>(regs.size());
        for (Registration reg : regs) {
            ScheduleSlot slot = slots.get(reg.getSlotId());
            if (slot != null) {
                PriceQueryApi.PriceSnapshot price = prices.get(slot.getPriceItemCode());
                result.add(toVO(reg, slot, price == null ? null : price.unitPrice()));
            }
        }
        return result;
    }

    private void checkBookable(ScheduleSlot slot, LocalDateTime now) {
        BizException.throwIf(slot.getStatus() != ScheduleSlot.STATUS_OPEN || slot.getOpenTime().isAfter(now),
                ScheduleErrorCode.SLOT_NOT_OPEN);
        BizException.throwIf(!slot.endAt().isAfter(now), ScheduleErrorCode.SLOT_PASSED);
    }

    private Registration newRegistration(Long patientId, ScheduleSlot slot, Money price, LocalDateTime now) {
        Registration reg = new Registration();
        // 合并写入走自定义批量 SQL，审计字段在这里填好
        Long operator = SecurityUtils.currentUserIdOrNull();
        reg.setId(IdWorker.getId());
        reg.setCreateTime(now);
        reg.setUpdateTime(now);
        reg.setCreateBy(operator == null ? 0L : operator);
        reg.setUpdateBy(operator == null ? 0L : operator);
        reg.setVersion(0);
        reg.setRegNo(BizNoGenerator.next(REG_NO_PREFIX, clock));
        reg.setPatientId(patientId);
        reg.setSlotId(slot.getId());
        if (requiresPayment(slot, price)) {
            reg.setStatus(RegistrationStatus.PENDING_PAYMENT);
            reg.setExpireTime(now.plus(properties.paymentTimeout()));
        } else {
            reg.setStatus(RegistrationStatus.BOOKED);
            reg.setExpireTime(slot.endAt());
        }
        return reg;
    }

    private boolean requiresPayment(ScheduleSlot slot, Money price) {
        if (price == null || price.isZero()) {
            return false;
        }
        return !(slot.getSlotType() == SlotType.PHYSICAL_EXAM.code() && properties.examSchoolPaid());
    }

    private static int chargeSourceOf(int slotType) {
        if (slotType == SlotType.PHYSICAL_EXAM.code()) {
            return 3;
        }
        return slotType == SlotType.VACCINE.code() ? 4 : 1;
    }

    private Registration findByRegNo(String regNo) {
        Registration reg = registrationMapper.selectOne(Wrappers.<Registration>lambdaQuery()
                .eq(Registration::getRegNo, regNo));
        if (reg == null) {
            throw new BizException(ScheduleErrorCode.BOOKING_NOT_FOUND);
        }
        return reg;
    }

    private BookingVO toVO(Registration reg, ScheduleSlot slot, Money price) {
        String deptName = orgQueryApi.deptNames(List.of(slot.getDeptId())).get(slot.getDeptId());
        String doctorName = orgQueryApi.userNicknames(List.of(slot.getDoctorId())).get(slot.getDoctorId());
        return new BookingVO(reg.getRegNo(), reg.getStatus(), slot.getSlotType(), slot.getCampusId(), deptName,
                doctorName, slot.getSlotDate(), slot.getStartTime(), slot.getEndTime(), price,
                reg.getStatus() == RegistrationStatus.PENDING_PAYMENT ? reg.getExpireTime() : null,
                reg.getCreateTime());
    }
}
