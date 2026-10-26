package io.github.ryancamus.clinic.charge.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import io.github.ryancamus.clinic.charge.domain.dto.CheckoutDTO.InsuranceRegistration;
import io.github.ryancamus.clinic.charge.domain.entity.ChargeOrder;
import io.github.ryancamus.clinic.charge.domain.entity.ChargeOrderItem;
import io.github.ryancamus.clinic.charge.domain.entity.InsuranceSettlement;
import io.github.ryancamus.clinic.charge.enums.ChargeErrorCode;
import io.github.ryancamus.clinic.charge.fee.FeeCalculator.Breakdown;
import io.github.ryancamus.clinic.charge.fee.FeeCalculator.FeeLine;
import io.github.ryancamus.clinic.charge.fee.FeeCalculator;
import io.github.ryancamus.clinic.charge.fee.FeePolicyResolver;
import io.github.ryancamus.clinic.charge.insurance.InsuranceGateway.Insured;
import io.github.ryancamus.clinic.charge.insurance.InsuranceGateway.Settlement;
import io.github.ryancamus.clinic.charge.insurance.InsuranceGateway;
import io.github.ryancamus.clinic.charge.mapper.ChargeOrderItemMapper;
import io.github.ryancamus.clinic.charge.mapper.ChargeOrderMapper;
import io.github.ryancamus.clinic.charge.mapper.InsuranceSettlementMapper;
import io.github.ryancamus.clinic.common.exception.BizException;
import io.github.ryancamus.clinic.common.money.Money;
import io.github.ryancamus.clinic.patient.api.PatientQueryApi;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 医保结算，在收费窗口同步完成，与真实流程一致：
 * <ol>
 *   <li>划价时预结算（{@link InsuranceGateway#preSettle}，≈2206），得出统筹与自付；</li>
 *   <li>收费时先正式结算（≈2207），医保中心确认统筹金额后，再向患者收取自付部分；</li>
 *   <li>自付收不上来（校园卡余额不足、付款码失败等）就撤销结算（≈2208），这笔收费整体不成立；</li>
 *   <li>退费时撤销原结算、按剩余明细重新结算（医保结算不能按单项部分撤销），再退还自付差额。</li>
 * </ol>
 * 每次调用医保前先写一条“结算中”记录：网络超时等结果未知时，记录还在，撤销确认之前这张收费单不能再收费，
 * 不会在医保中心留下本系统不知道的结算。
 * <p>
 * <b>医保结算平台未接入时</b>（没有 {@link InsuranceGateway} 实现）：划价按本院配置的报销规则估算；
 * 收费员在医保部门提供的结算终端上办理结算，再把结算单号、统筹金额登记到本系统（记录登记人）；
 * 撤销、退费重结同样在终端办理后登记。本系统不会自行生成任何医保结算结果。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InsuranceSettlementService {

    /** 医保结算平台适配器；未接入时为空，改由收费员在医保结算终端办理后登记 */
    private final ObjectProvider<InsuranceGateway> gateway;
    private final FeePolicyResolver policies;
    private final InsuranceSettlementMapper settlementMapper;
    private final ChargeOrderMapper orderMapper;
    private final ChargeOrderItemMapper itemMapper;
    private final PatientQueryApi patientQueryApi;
    private final MeterRegistry meterRegistry;
    private final Clock clock;

    // ------------------------------------------------------------------ 窗口收费

    /** 医保结算平台是否已接入 */
    public boolean connected() {
        return gateway.getIfAvailable() != null;
    }

    /**
     * 划价时估算统筹与自付。已接入时由医保中心预结算；未接入时按本院配置的报销规则估算，以医保终端结算为准。
     */
    public Breakdown estimate(Insured insured, List<FeeLine> lines) {
        InsuranceGateway g = gateway.getIfAvailable();
        return g != null ? g.preSettle(insured, lines)
                : FeeCalculator.calculate(lines, policies.resolve(insured.insuranceType()));
    }

    /**
     * 收费窗口正式结算。返回已结算记录；医保中心算出的统筹与划价不一致时报错（记录保留，由调用方撤销），
     * 由收费员作废后重新划价。
     *
     * @param certType     就诊凭证类型，为空时按居民身份证（读患者档案中的实名信息）
     * @param certNo       凭证编号：医保电子凭证扫码所得、读卡所得；为空由适配器按档案读取
     * @param registration 未接入医保平台时，收费员在医保终端结算后登记的结果
     * @param operatorId   收费员
     */
    public InsuranceSettlement settleAtCounter(ChargeOrder order, String certType, String certNo,
                                               InsuranceRegistration registration, Long operatorId) {
        String type = certType == null || certType.isBlank() ? InsuranceGateway.CertType.ID_CARD : certType;
        BizException.throwIf(!InsuranceGateway.CertType.valid(type), ChargeErrorCode.CERT_INVALID);
        // 正常情况下不会有：未撤销的结算会使上次结算转为收款异常，财务处理（确认撤销）之前收费单被锁住
        BizException.throwIf(!outstanding(order.getId()).isEmpty(), ChargeErrorCode.INSURANCE_CANCEL_PENDING);

        long attempts = settlementMapper.selectCount(Wrappers.<InsuranceSettlement>lambdaQuery()
                .eq(InsuranceSettlement::getChargeOrderId, order.getId()));
        String bizNo = attempts == 0 ? order.getChargeNo() : order.getChargeNo() + "-" + (attempts + 1);
        InsuranceGateway g = gateway.getIfAvailable();
        if (g == null) {
            return registerAtCounter(order, bizNo, type, registration, operatorId);
        }
        InsuranceSettlement row = newRow(order.getId(), bizNo, type, order.getInsuranceAmount());
        try {
            settlementMapper.insert(row);
        } catch (DuplicateKeyException e) {
            // 另一个窗口正在为同一张收费单结算
            throw new BizException(ChargeErrorCode.PAY_IN_PROGRESS);
        }

        Settlement s;
        try {
            s = g.settle(bizNo, insured(order, type, certNo), remainingLines(order.getId()));
        } catch (RuntimeException e) {
            // 结果未知：可能医保中心已经结算、只是响应丢了。“结算中”记录保留，由调用方撤销并确认
            log.warn("医保结算失败 chargeNo={} bizNo={}", order.getChargeNo(), bizNo, e);
            throw new BizException(ChargeErrorCode.INSURANCE_SETTLE_FAILED);
        }
        fill(row, s);
        settlementMapper.updateById(row);
        if (!s.fundPay().equals(order.getInsuranceAmount())) {
            meterRegistry.counter("clinic.insurance.fund.mismatch").increment();
            log.warn("医保结算金额与划价不一致 chargeNo={} expected={} actual={}", order.getChargeNo(),
                    order.getInsuranceAmount(), s.fundPay());
            throw new BizException(ChargeErrorCode.INSURANCE_AMOUNT_CHANGED);
        }
        return row;
    }

    /** 未接入：登记收费员在医保结算终端办理的结果 */
    private InsuranceSettlement registerAtCounter(ChargeOrder order, String bizNo, String certType,
                                                  InsuranceRegistration registration, Long operatorId) {
        BizException.throwIf(registration == null, ChargeErrorCode.INSURANCE_NOT_CONNECTED);
        InsuranceSettlement row = newRow(order.getId(), bizNo, certType, order.getInsuranceAmount());
        row.setSource(InsuranceSettlement.SOURCE_MANUAL);
        row.setStatus(InsuranceSettlement.SETTLED);
        row.setSetlId(registration.setlId().trim());
        row.setFundPay(registration.fundPay());
        row.setMedfee(order.getTotalAmount());
        row.setCashPay(order.getTotalAmount().subtract(registration.fundPay()));
        row.setSettleTime(LocalDateTime.now(clock));
        row.setOperatorId(operatorId);
        try {
            settlementMapper.insert(row);
        } catch (DuplicateKeyException e) {
            throw new BizException(ChargeErrorCode.PAY_IN_PROGRESS);
        }
        if (!registration.fundPay().equals(order.getInsuranceAmount())) {
            // 终端上的结算已经发生，记录保留：转为收款异常，由收费员在终端撤销、财务确认
            meterRegistry.counter("clinic.insurance.fund.mismatch").increment();
            throw new BizException(ChargeErrorCode.INSURANCE_AMOUNT_CHANGED, "登记的统筹金额 " + registration.fundPay()
                    + " 元与划价的 " + order.getInsuranceAmount() + " 元不一致：请在医保终端撤销该笔结算，作废后重新划价");
        }
        return row;
    }

    /**
     * 撤销一次结算（收款失败时调用）。
     *
     * @return 是否已撤销；医保中心暂时不可用、或结算在医保终端办理（需人工撤销）时返回 false，记录保留
     */
    public boolean cancelQuietly(InsuranceSettlement row) {
        try {
            cancel(row);
            return true;
        } catch (RuntimeException e) {
            log.error("医保结算撤销失败，等待下次重试 bizNo={}", row.getBizNo(), e);
            meterRegistry.counter("clinic.insurance.cancel.failed").increment();
            return false;
        }
    }

    /**
     * 收款失败、或财务处理收款异常时：撤销这张（仍未付清的）收费单上所有未撤销的窗口结算，包括结果未知的“结算中”记录。
     *
     * @return 是否已全部撤销；false 表示医保中心暂时不可用或撤销被拒，记录保留为“未撤销”，不能视为已撤回
     */
    public boolean cancelOutstanding(Long chargeOrderId) {
        boolean all = true;
        for (InsuranceSettlement r : outstanding(chargeOrderId)) {
            all &= cancelQuietly(r);
        }
        return all;
    }

    /** 未撤销的窗口结算（结算中、已结算） */
    public List<InsuranceSettlement> outstanding(Long chargeOrderId) {
        return listByOrder(chargeOrderId).stream()
                .filter(r -> r.getStatus() != InsuranceSettlement.CANCELLED)
                .toList();
    }

    /**
     * 财务已在医保端手工撤销（本系统撤销一直失败时），登记处理人和说明。只用于未付清收费单上的窗口结算。
     */
    public void markCancelledManually(Long chargeOrderId, Long operatorId, String note) {
        for (InsuranceSettlement r : outstanding(chargeOrderId)) {
            if (r.getStatus() == InsuranceSettlement.CANCELLED) {
                continue;
            }
            r.setStatus(InsuranceSettlement.CANCELLED);
            r.setCancelTime(LocalDateTime.now(clock));
            r.setCancelBy(operatorId);
            r.setCancelNote(note);
            settlementMapper.updateById(r);
        }
    }

    // ------------------------------------------------------------------ 退费

    /**
     * 退费：撤销收费窗口的有效结算，再按剩余明细重新结算（业务号 = 退费单号）。由退费执行同步调用，
     * 必须在退还自付之前完成；失败时抛出异常，退费保持“退款中”由定时任务重试，每一步都可重复执行。
     * 调用时退费数量已在明细上预占，剩余明细即为退费后的明细。
     */
    public void resettleAfterRefund(Long chargeOrderId, String refundNo) {
        ChargeOrder order = orderMapper.selectById(chargeOrderId);
        if (order == null) {
            return;
        }
        List<InsuranceSettlement> rows = listByOrder(chargeOrderId);
        if (rows.isEmpty()) {
            return;
        }
        InsuranceGateway g = gateway.getIfAvailable();
        if (g == null || rows.stream().anyMatch(r -> r.getSource() == InsuranceSettlement.SOURCE_MANUAL)) {
            if (rows.stream().anyMatch(r -> r.getBizNo().equals(refundNo))
                    || rows.stream().allMatch(r -> r.getStatus() == InsuranceSettlement.CANCELLED)) {
                return; // 已登记过本次退费的撤销与重结
            }
            // 未接入：等收费员在医保终端撤销原结算、按剩余明细重新结算后登记（registerRefundResettlement）
            throw new BizException(ChargeErrorCode.INSURANCE_NOT_CONNECTED, "请在医保结算终端撤销原结算并按剩余项目重新结算后登记");
        }
        String certType = rows.getFirst().getCertType();
        for (InsuranceSettlement r : rows) {
            boolean thisRefund = r.getBizNo().equals(refundNo);
            if (thisRefund && r.getStatus() == InsuranceSettlement.SETTLED) {
                return;
            }
            if (!thisRefund && r.getStatus() != InsuranceSettlement.CANCELLED) {
                cancel(r);
            }
        }

        List<ChargeOrderItem> items = items(chargeOrderId);
        Money expected = expectedAfterRefund(items);
        List<FeeLine> lines = remainingLines(items);
        if (lines.isEmpty() || expected.isZero()) {
            return;
        }
        InsuranceSettlement row = rows.stream().filter(r -> r.getBizNo().equals(refundNo)).findFirst()
                .orElseGet(() -> {
                    InsuranceSettlement n = newRow(chargeOrderId, refundNo, certType, expected);
                    settlementMapper.insert(n);
                    return n;
                });
        Settlement s = g.settle(refundNo, insured(order, certType, null), lines);
        fill(row, s);
        settlementMapper.updateById(row);
        if (!s.fundPay().equals(expected)) {
            // 退费后的自付差额已按划价口径确定，医保中心结果不同时只告警，由医保管理员核对差额
            meterRegistry.counter("clinic.insurance.fund.mismatch").increment();
            log.warn("退费重结金额与预期不一致 chargeNo={} bizNo={} expected={} actual={}", order.getChargeNo(),
                    refundNo, expected, s.fundPay());
        }
    }

    /**
     * 医保平台未接入时的退费：收费员已在医保结算终端撤销原结算，并按退费后剩余的项目重新结算，登记结果。
     *
     * @param registration 重新结算的结果；退费后没有医保报销部分（全部退完）时为空
     */
    public void registerRefundResettlement(Long chargeOrderId, String refundNo, InsuranceRegistration registration,
                                           Long operatorId) {
        Money expected = expectedAfterRefund(items(chargeOrderId));
        BizException.throwIf(expected.isZero() != (registration == null), ChargeErrorCode.INSURANCE_REGISTRATION_INVALID,
                expected.isZero() ? "退费后没有医保报销部分，只需确认已在终端撤销原结算" : "请登记重新结算的结算单号和统筹金额");
        BizException.throwIf(registration != null && !registration.fundPay().equals(expected),
                ChargeErrorCode.INSURANCE_REGISTRATION_INVALID, "重新结算的统筹金额应为 " + expected + " 元");
        List<InsuranceSettlement> rows = listByOrder(chargeOrderId);
        BizException.throwIf(rows.stream().anyMatch(r -> r.getBizNo().equals(refundNo)),
                ChargeErrorCode.REFUND_STATE_CHANGED);
        String certType = rows.isEmpty() ? InsuranceGateway.CertType.ID_CARD : rows.getFirst().getCertType();
        markCancelledManually(chargeOrderId, operatorId, "退费 " + refundNo + "：已在医保终端撤销");
        if (registration != null) {
            ChargeOrder order = orderMapper.selectById(chargeOrderId);
            InsuranceSettlement row = newRow(chargeOrderId, refundNo, certType, expected);
            row.setSource(InsuranceSettlement.SOURCE_MANUAL);
            row.setStatus(InsuranceSettlement.SETTLED);
            row.setSetlId(registration.setlId().trim());
            row.setFundPay(registration.fundPay());
            row.setSettleTime(LocalDateTime.now(clock));
            row.setOperatorId(operatorId);
            log.info("登记退费后医保重结 chargeNo={} refundNo={}", order.getChargeNo(), refundNo);
            settlementMapper.insert(row);
        }
    }

    /** 退费后（退费数量已在明细上预占）剩余的统筹金额 */
    private static Money expectedAfterRefund(List<ChargeOrderItem> items) {
        return items.stream().map(InsuranceSettlementService::remainingInsurance).reduce(Money.ZERO, Money::add);
    }

    // ------------------------------------------------------------------ 查询

    /** 本系统某天的有效结算，供对账 */
    public List<InsuranceSettlement> settledOn(LocalDate date) {
        return settlementMapper.selectList(Wrappers.<InsuranceSettlement>lambdaQuery()
                .eq(InsuranceSettlement::getStatus, InsuranceSettlement.SETTLED)
                .ge(InsuranceSettlement::getSettleTime, date.atStartOfDay())
                .lt(InsuranceSettlement::getSettleTime, date.plusDays(1).atStartOfDay()));
    }

    public List<InsuranceSettlement> listByOrder(Long chargeOrderId) {
        return settlementMapper.selectList(Wrappers.<InsuranceSettlement>lambdaQuery()
                .eq(InsuranceSettlement::getChargeOrderId, chargeOrderId)
                .orderByAsc(InsuranceSettlement::getId));
    }

    // ------------------------------------------------------------------ 内部方法

    private void cancel(InsuranceSettlement row) {
        InsuranceGateway g = gateway.getIfAvailable();
        if (g == null || row.getSource() == InsuranceSettlement.SOURCE_MANUAL) {
            throw new BizException(ChargeErrorCode.INSURANCE_NOT_CONNECTED, "结算单 " + row.getSetlId()
                    + " 在医保结算终端办理，需在终端撤销后登记");
        }
        g.cancel(row.getBizNo(), row.getSetlId());
        row.setStatus(InsuranceSettlement.CANCELLED);
        row.setCancelTime(LocalDateTime.now(clock));
        settlementMapper.updateById(row);
    }

    private InsuranceSettlement newRow(Long chargeOrderId, String bizNo, String certType, Money expected) {
        InsuranceSettlement row = new InsuranceSettlement();
        row.setChargeOrderId(chargeOrderId);
        row.setBizNo(bizNo);
        row.setStatus(InsuranceSettlement.SETTLING);
        row.setSource(InsuranceSettlement.SOURCE_COUNTER);
        row.setCertType(certType);
        row.setExpectedFundPay(expected);
        return row;
    }

    private static void fill(InsuranceSettlement row, Settlement s) {
        row.setSetlId(s.setlId());
        row.setStatus(InsuranceSettlement.SETTLED);
        row.setMedfee(s.medfee());
        row.setFundPay(s.fundPay());
        row.setCashPay(s.cashPay());
        row.setSettleTime(s.settledAt());
    }

    public Insured insured(ChargeOrder order, String certType, String certNo) {
        int insuranceType = patientQueryApi.findById(order.getPatientId()).map(p -> p.insuranceType()).orElse(0);
        return new Insured(order.getPatientId(), insuranceType, certType, certNo);
    }

    private List<ChargeOrderItem> items(Long chargeOrderId) {
        return itemMapper.selectList(Wrappers.<ChargeOrderItem>lambdaQuery()
                .eq(ChargeOrderItem::getChargeOrderId, chargeOrderId)
                .orderByAsc(ChargeOrderItem::getId));
    }

    /** 收费单尚未退的明细，用于医保结算的费用明细上传 */
    public List<FeeLine> remainingLines(Long chargeOrderId) {
        return remainingLines(items(chargeOrderId));
    }

    private static List<FeeLine> remainingLines(List<ChargeOrderItem> items) {
        return items.stream().filter(i -> i.getQty() > i.getRefundedQty())
                .map(i -> new FeeLine(i.getItemCode(), i.getItemName(), i.getItemType(), i.getUnitPrice(),
                        i.getQty() - i.getRefundedQty(), i.getInsuranceRatio().signum() > 0))
                .toList();
    }

    /** 某行退过一部分后剩余的统筹金额：原值减去已退部分（与退费拆分口径一致） */
    private static Money remainingInsurance(ChargeOrderItem i) {
        if (i.getRefundedQty() == 0) {
            return i.getInsuranceAmount();
        }
        if (i.getRefundedQty() >= i.getQty()) {
            return Money.ZERO;
        }
        return new Money(i.getInsuranceAmount().amount()
                .multiply(BigDecimal.valueOf(i.getQty() - i.getRefundedQty()))
                .divide(BigDecimal.valueOf(i.getQty()), Money.SCALE, RoundingMode.UP));
    }
}
