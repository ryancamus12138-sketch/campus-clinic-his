package io.github.ryancamus.clinic.finance.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.github.ryancamus.clinic.common.exception.BizException;
import io.github.ryancamus.clinic.common.money.Money;
import io.github.ryancamus.clinic.common.result.PageResult;
import io.github.ryancamus.clinic.finance.api.FinanceShiftApi;
import io.github.ryancamus.clinic.finance.domain.entity.CashierShift;
import io.github.ryancamus.clinic.finance.domain.vo.ShiftTotals;
import io.github.ryancamus.clinic.finance.domain.vo.ShiftVO;
import io.github.ryancamus.clinic.finance.enums.FinanceErrorCode;
import io.github.ryancamus.clinic.finance.mapper.CashierShiftMapper;
import io.github.ryancamus.clinic.framework.util.BizNoGenerator;
import io.github.ryancamus.clinic.system.api.OrgQueryApi;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 收费员交班。
 * <p>
 * 交班金额全部由资金流水按班次汇总得出。收费员录入实点现金，有银行卡收退款的再录入 POS 结算单金额，
 * 不一致时必须填写说明；财务复核记录复核人、时间和意见。交班后班次关闭，之后的收费自动进入新班次，
 * 已交班的汇总不会再变化——退费记在办理退费的班次，而不是原收费的班次。
 */
@Service
@RequiredArgsConstructor
public class ShiftService implements FinanceShiftApi {

    private static final String SHIFT_NO_PREFIX = "S";

    private final CashierShiftMapper shiftMapper;
    private final OrgQueryApi orgQueryApi;
    private final Clock clock;

    @Override
    public Long currentShiftOf(Long shiftId) {
        CashierShift shift = shiftMapper.selectById(shiftId);
        if (shift == null || shift.getStatus() == CashierShift.OPEN) {
            return shiftId;
        }
        return ensureOpenShift(shift.getCashierId(), shift.getCampusId());
    }

    @Override
    public Long ensureOpenShift(Long cashierId, Long campusId) {
        CashierShift open = shiftMapper.selectOpen(cashierId);
        if (open != null) {
            return open.getId();
        }
        CashierShift shift = new CashierShift();
        shift.setShiftNo(BizNoGenerator.next(SHIFT_NO_PREFIX, clock));
        shift.setCampusId(campusId);
        orgQueryApi.defaultChargePoint(campusId).ifPresent(p -> shift.setChargePointId(p.id()));
        shift.setCashierId(cashierId);
        shift.setStatus(CashierShift.OPEN);
        shift.setStartTime(LocalDateTime.now(clock));
        shift.setCashAmount(Money.ZERO);
        shift.setCardAmount(Money.ZERO);
        shift.setOnlineAmount(Money.ZERO);
        shift.setRefundAmount(Money.ZERO);
        shift.setOrderCount(0);
        try {
            shiftMapper.insert(shift);
            return shift.getId();
        } catch (DuplicateKeyException e) {
            // 同一收费员并发开班：唯一约束只允许一个当班记录
            return shiftMapper.selectOpen(cashierId).getId();
        }
    }

    /** 当前班次（实时汇总），未开班返回空 */
    public Optional<ShiftVO> current(Long cashierId) {
        return Optional.ofNullable(shiftMapper.selectOpen(cashierId)).map(s -> toVO(s, shiftMapper.sumLedger(s.getId())));
    }

    /** 本班系统应有的 POS 净额（银行卡收款 − 银行卡退款），交班前与 POS 机结算单核对 */
    public BigDecimal posExpected(Long shiftId) {
        return shiftMapper.sumLedger(shiftId).getPosExpected();
    }

    /**
     * 交班：汇总本班流水，核对实点现金和 POS 机结算单，状态 当班 → 已交班（条件更新，重复提交只生效一次）。
     * <ul>
     *   <li>现金：实点与应有（现金收 − 现金退）不一致必须说明；</li>
     *   <li>银行卡：本班有银行卡收款或退款时，必须录入 POS 机结算单金额，与应有（银行卡收 − 银行卡退）不一致必须说明。</li>
     * </ul>
     *
     * @param posCounted POS 机结算单金额；本班没有银行卡业务时可为空
     */
    public ShiftVO handOver(Long shiftId, Long cashierId, BigDecimal countedCash, BigDecimal posCounted,
                            String diffNote) {
        CashierShift shift = require(shiftId);
        BizException.throwIf(!shift.getCashierId().equals(cashierId), FinanceErrorCode.NOT_YOUR_SHIFT);
        BizException.throwIf(shift.getStatus() != CashierShift.OPEN, FinanceErrorCode.SHIFT_NOT_OPEN);
        ShiftTotals totals = shiftMapper.sumLedger(shiftId);
        String note = diffNote == null || diffNote.isBlank() ? null : diffNote.trim();
        // 长短款必须由交班人当场说明，责任可追溯
        BizException.throwIf(countedCash.compareTo(totals.getExpectedCash()) != 0 && note == null,
                FinanceErrorCode.DIFF_NOTE_REQUIRED, "实点现金 " + countedCash + " 元，应有 " + totals.getExpectedCash()
                        + " 元，请填写长短款说明");
        BigDecimal posExpected = totals.getPosExpected();
        boolean hasPos = totals.getPosCount() != null && totals.getPosCount() > 0;
        BizException.throwIf(hasPos && posCounted == null, FinanceErrorCode.POS_COUNT_REQUIRED);
        BizException.throwIf(posCounted != null && posCounted.compareTo(posExpected) != 0 && note == null,
                FinanceErrorCode.DIFF_NOTE_REQUIRED, "POS 结算单 " + posCounted + " 元，系统应有 " + posExpected
                        + " 元，请填写差额说明");
        BizException.throwIf(shiftMapper.handOver(shiftId, totals, countedCash, posCounted, note,
                LocalDateTime.now(clock)) == 0, FinanceErrorCode.SHIFT_NOT_OPEN);
        return toVO(require(shiftId), null);
    }

    /** 财务复核：记录复核人、时间、意见；收费员本人不能复核自己的班次 */
    public ShiftVO review(Long shiftId, Long reviewerId, String note) {
        CashierShift shift = require(shiftId);
        BizException.throwIf(reviewerId.equals(shift.getCashierId()), FinanceErrorCode.REVIEWER_IS_CASHIER);
        BizException.throwIf(shiftMapper.review(shiftId, reviewerId, note == null || note.isBlank() ? null : note.trim(),
                LocalDateTime.now(clock)) == 0, FinanceErrorCode.SHIFT_NOT_HANDED_OVER);
        return toVO(require(shiftId), null);
    }

    public PageResult<ShiftVO> page(LocalDate date, Long cashierId, long page, long size) {
        Page<CashierShift> p = shiftMapper.selectPage(Page.of(page, size), Wrappers.<CashierShift>lambdaQuery()
                .eq(cashierId != null, CashierShift::getCashierId, cashierId)
                .ge(date != null, CashierShift::getStartTime, date == null ? null : date.atStartOfDay())
                .lt(date != null, CashierShift::getStartTime, date == null ? null : date.plusDays(1).atStartOfDay())
                .orderByDesc(CashierShift::getId));
        Map<Long, String> names = orgQueryApi.userNicknames(
                p.getRecords().stream().map(CashierShift::getCashierId).distinct().toList());
        Map<Long, String> points = orgQueryApi.chargePointNames(
                p.getRecords().stream().map(CashierShift::getChargePointId).distinct().toList());
        List<ShiftVO> list = p.getRecords().stream().map(s -> {
            ShiftVO vo = toVO(s, s.getStatus() == CashierShift.OPEN ? shiftMapper.sumLedger(s.getId()) : null);
            return withName(vo, names.get(s.getCashierId()), points.get(s.getChargePointId()));
        }).toList();
        return new PageResult<>(list, p.getTotal(), p.getCurrent(), p.getSize());
    }

    private CashierShift require(Long id) {
        CashierShift shift = shiftMapper.selectById(id);
        if (shift == null) {
            throw new BizException(FinanceErrorCode.SHIFT_NOT_FOUND);
        }
        return shift;
    }

    /**
     * @param live 当班时传入实时汇总；已交班的用交班时写入的数字
     */
    private ShiftVO toVO(CashierShift s, ShiftTotals live) {
        BigDecimal cash = live != null ? live.getCashAmount() : s.getCashAmount().amount();
        BigDecimal card = live != null ? live.getCardAmount() : s.getCardAmount().amount();
        BigDecimal online = live != null ? live.getOnlineAmount() : s.getOnlineAmount().amount();
        BigDecimal refund = live != null ? live.getRefundAmount() : s.getRefundAmount().amount();
        BigDecimal expected = live != null ? live.getExpectedCash() : s.getExpectedCash();
        Integer count = live != null ? live.getOrderCount() : s.getOrderCount();
        BigDecimal counted = s.getCountedCash() == null ? null : s.getCountedCash().amount();
        BigDecimal diff = counted == null || expected == null ? null : counted.subtract(expected);
        BigDecimal posExpected = live != null ? live.getPosExpected() : s.getPosExpected();
        BigDecimal posCounted = s.getPosCounted();
        BigDecimal posDiff = posCounted == null || posExpected == null ? null : posCounted.subtract(posExpected);
        Integer posCount = live != null ? live.getPosCount() : null;
        return new ShiftVO(s.getId(), s.getShiftNo(), s.getCashierId(), null, s.getChargePointId(), null,
                s.getStatus(), s.getStartTime(),
                s.getEndTime(), cash, card, online, refund, expected, counted, diff, count, posExpected, posCounted,
                posDiff, posCount, s.getDiffNote(), s.getReviewerId(), s.getReviewTime(), s.getReviewNote());
    }

    private static ShiftVO withName(ShiftVO v, String name, String pointName) {
        return new ShiftVO(v.id(), v.shiftNo(), v.cashierId(), name, v.chargePointId(), pointName, v.status(),
                v.startTime(), v.endTime(),
                v.cashAmount(), v.cardAmount(), v.onlineAmount(), v.refundAmount(), v.expectedCash(),
                v.countedCash(), v.cashDiff(), v.orderCount(), v.posExpected(), v.posCounted(), v.posDiff(),
                v.posCount(), v.diffNote(), v.reviewerId(), v.reviewTime(), v.reviewNote());
    }
}
