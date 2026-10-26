package io.github.ryancamus.clinic.charge;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import io.github.ryancamus.clinic.charge.campuscard.mock.MockCampusCardGateway;
import io.github.ryancamus.clinic.charge.domain.dto.ChargeCreateDTO;
import io.github.ryancamus.clinic.charge.domain.dto.ChargeItemDTO;
import io.github.ryancamus.clinic.charge.domain.dto.CheckoutDTO.Tender;
import io.github.ryancamus.clinic.charge.domain.dto.CheckoutDTO;
import io.github.ryancamus.clinic.charge.domain.dto.RefundApplyDTO;
import io.github.ryancamus.clinic.charge.domain.entity.ChargeOrderItem;
import io.github.ryancamus.clinic.charge.domain.entity.CounterCheckout;
import io.github.ryancamus.clinic.charge.domain.entity.InsuranceSettlement;
import io.github.ryancamus.clinic.charge.domain.entity.PayOrder;
import io.github.ryancamus.clinic.charge.domain.entity.RefundTender;
import io.github.ryancamus.clinic.charge.domain.vo.ChargeOrderVO;
import io.github.ryancamus.clinic.charge.domain.vo.RefundVO;
import io.github.ryancamus.clinic.charge.enums.ChargeErrorCode;
import io.github.ryancamus.clinic.charge.enums.ChargeStatus;
import io.github.ryancamus.clinic.charge.insurance.InsuranceGateway.Settlement;
import io.github.ryancamus.clinic.charge.insurance.mock.MockInsuranceGateway;
import io.github.ryancamus.clinic.charge.mapper.ChargeOrderItemMapper;
import io.github.ryancamus.clinic.charge.mapper.ChargeOrderMapper;
import io.github.ryancamus.clinic.charge.mapper.CounterCheckoutMapper;
import io.github.ryancamus.clinic.charge.mapper.InsuranceSettlementMapper;
import io.github.ryancamus.clinic.charge.mapper.PayOrderMapper;
import io.github.ryancamus.clinic.charge.mapper.RefundTenderMapper;
import io.github.ryancamus.clinic.charge.service.ChargeOrderService;
import io.github.ryancamus.clinic.charge.service.CounterCheckoutService;
import io.github.ryancamus.clinic.charge.service.RefundService;
import io.github.ryancamus.clinic.common.exception.BizException;
import io.github.ryancamus.clinic.common.money.Money;
import io.github.ryancamus.clinic.finance.api.ChannelBillPort;
import io.github.ryancamus.clinic.finance.domain.entity.ReconDiff;
import io.github.ryancamus.clinic.finance.mapper.ReconDiffMapper;
import io.github.ryancamus.clinic.finance.service.ReconService;
import io.github.ryancamus.clinic.support.IntegrationTest;
import io.github.ryancamus.clinic.support.MutableClock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * M25：医保结算在收费窗口同步完成。
 * <ul>
 *   <li>医保中心随机失败 30% 时，失败的那次收费不收钱、不留结算，收费员重收即可；
 *       最终每张收费单恰好一条有效结算、一笔收款，基金支付与划价一致；</li>
 *   <li>医保中心已结算但响应丢失时，本系统按同一业务号撤销，不留下医保中心有、本系统没有的结算；</li>
 *   <li>收款失败后医保撤销也失败：保留异常、阻止重复收费，重试撤销成功或财务登记手工撤销后才解除；</li>
 *   <li>退费时撤销原结算、按剩余重结；日终与医保中心对明细账能发现两边各自多出的记录。</li>
 * </ul>
 */
@IntegrationTest
class InsuranceSettlementIntegrationTest {

    private static final long STUDENT = 90001L;
    private static final long CASHIER = 2L;

    @Autowired
    private ChargeOrderService chargeOrderService;
    @Autowired
    private RefundService refundService;
    @Autowired
    private MockInsuranceGateway insurance;
    @Autowired
    private InsuranceSettlementMapper settlementMapper;
    @Autowired
    private ChargeOrderItemMapper itemMapper;
    @Autowired
    private ChargeOrderMapper orderMapper;
    @Autowired
    private PayOrderMapper payOrderMapper;
    @Autowired
    private ReconService reconService;
    @Autowired
    private ReconDiffMapper diffMapper;
    @Autowired
    private MutableClock clock;
    @Autowired
    private CounterCheckoutService checkout;
    @Autowired
    private CounterCheckoutMapper checkoutMapper;
    @Autowired
    private RefundTenderMapper refundTenderMapper;
    @Autowired
    private MockCampusCardGateway campusCard;

    @AfterEach
    void restore() {
        insurance.setFailRate(0);
        insurance.loseNextResponses(0);
        insurance.failNextCancels(0);
        clock.reset();
    }

    @Test
    void failedSettlementsTakeNoMoneyAndRetriesSettleEachOrderExactlyOnce() {
        insurance.setFailRate(0.3);
        List<ChargeOrderVO> orders = new ArrayList<>();
        int failedAttempts = 0;
        for (int i = 0; i < 50; i++) {
            ChargeOrderVO order = newOrder("JC-XCG", 1);
            orders.add(order);
            for (int attempt = 1; ; attempt++) {
                try {
                    checkout.cashPay(order.id(), order.payableAmount(), UUID.randomUUID().toString(), CASHIER);
                    break;
                } catch (BizException e) {
                    assertThat(e.getErrorCode()).isEqualTo(ChargeErrorCode.INSURANCE_SETTLE_FAILED);
                    // 失败的这次没有收钱，收费单仍可收费
                    assertThat(successfulPays(order.id())).isZero();
                    assertThat(orderMapper.selectById(order.id()).getStatus()).isEqualTo(ChargeStatus.UNPAID.code());
                    failedAttempts++;
                    assertThat(attempt).isLessThan(30);
                }
            }
        }
        insurance.setFailRate(0);

        Set<String> centerBizNos = new HashSet<>();
        insurance.statement(LocalDate.now(clock)).forEach(s -> centerBizNos.add(s.bizNo()));
        int settled = 0;
        int mismatched = 0;
        int leftover = 0;
        for (ChargeOrderVO o : orders) {
            List<InsuranceSettlement> rows = rows(o.id());
            for (InsuranceSettlement r : rows) {
                if (r.getStatus() == InsuranceSettlement.SETTLED) {
                    settled++;
                    if (!r.getFundPay().equals(o.insuranceAmount()) || !centerBizNos.contains(r.getBizNo())) {
                        mismatched++;
                    }
                } else if (r.getStatus() == InsuranceSettlement.SETTLING || centerBizNos.contains(r.getBizNo())) {
                    leftover++;
                }
            }
            assertThat(successfulPays(o.id())).isEqualTo(1);
        }
        System.out.printf("[M25] settle: orders=50 failRate=0.3 failedAttempts=%d settled=%d mismatched=%d leftover=%d%n",
                failedAttempts, settled, mismatched, leftover);
        assertThat(failedAttempts).isPositive();
        assertThat(settled).isEqualTo(50);
        assertThat(mismatched).isZero();
        assertThat(leftover).isZero();
        assertThat(orders.getFirst().insuranceAmount()).isEqualTo(Money.of("16.00"));
    }

    @Test
    void settlementWhoseResponseWasLostIsCancelledAtTheCenter() {
        ChargeOrderVO order = newOrder("JC-XCG", 1);
        insurance.loseNextResponses(1);
        assertThatThrownBy(() -> checkout.cashPay(order.id(), order.payableAmount(),
                UUID.randomUUID().toString(), CASHIER)).isInstanceOf(BizException.class);
        // 医保中心实际已结算，本系统按同一业务号撤销，两边都不留这笔
        assertThat(insurance.hasSettlement(order.chargeNo())).isFalse();
        assertThat(rows(order.id())).singleElement()
                .satisfies(r -> assertThat(r.getStatus()).isEqualTo(InsuranceSettlement.CANCELLED));

        checkout.cashPay(order.id(), order.payableAmount(), UUID.randomUUID().toString(), CASHIER);
        InsuranceSettlement ok = rows(order.id()).getLast();
        assertThat(ok.getStatus()).isEqualTo(InsuranceSettlement.SETTLED);
        assertThat(ok.getBizNo()).isEqualTo(order.chargeNo() + "-2");
        assertThat(ok.getCertType()).isEqualTo("02");
        System.out.println("[M25] lost response: cancelled at center, retry settled as " + ok.getBizNo());
    }

    @Test
    void refundCancelsTheSettlementAndResettlesWhatRemains() {
        ChargeOrderVO order = cashPaid("JC-XCG", 2);   // 40.00，基金 32.00
        InsuranceSettlement first = settled(order.id()).getFirst();
        assertThat(first.getFundPay()).isEqualTo(Money.of("32.00"));

        RefundVO partial = refund(order.id(), 1);
        List<InsuranceSettlement> afterPartial = rows(order.id());
        assertThat(afterPartial).hasSize(2);
        assertThat(afterPartial.get(0).getStatus()).isEqualTo(InsuranceSettlement.CANCELLED);
        assertThat(afterPartial.get(1).getBizNo()).isEqualTo(partial.refundNo());
        assertThat(afterPartial.get(1).getFundPay()).isEqualTo(Money.of("16.00"));

        refund(order.id(), 1);
        assertThat(settled(order.id())).isEmpty();
        Set<String> center = new HashSet<>();
        insurance.statement(LocalDate.now(clock)).forEach(s -> center.add(s.bizNo()));
        assertThat(center).doesNotContain(order.chargeNo(), partial.refundNo());
        System.out.println("[M25] refund: partial -> cancel + resettle 16.00; full -> cancelled, center clean");
    }

    @Test
    void refundWaitsWhileTheInsuranceCenterIsDownAndCompletesOnRetry() {
        ChargeOrderVO order = cashPaid("JC-XCG", 2);
        insurance.setFailRate(1);
        RefundVO pending = refund(order.id(), 1);
        // 医保撤销重结未完成之前不退钱
        assertThat(orderMapper.selectById(order.id()).getRefundedAmount()).isEqualTo(Money.ZERO);
        insurance.setFailRate(0);
        clock.travelTo(LocalDateTime.now(clock).plusMinutes(2));
        refundService.retry(100);
        // 医保撤销重结完成后，现金待收费员当面退还并确认
        assertThat(orderMapper.selectById(order.id()).getRefundedAmount()).isEqualTo(Money.ZERO);
        confirmCash(pending.id());
        assertThat(orderMapper.selectById(order.id()).getRefundedAmount()).isEqualTo(Money.of("4.00"));
        assertThat(settled(order.id())).singleElement()
                .satisfies(r -> assertThat(r.getBizNo()).isEqualTo(pending.refundNo()));
    }

    @Test
    void failedInsuranceCancelKeepsTheExceptionAndBlocksRechargingUntilFinanceConfirms() {
        // 学生血常规：40.00，统筹 32.00，自付 8.00；医保已结算，第二张校园卡余额不足，撤回时医保撤销失败
        ChargeOrderVO order = newOrder("JC-XCG", 2);
        String rich = card(Money.of("100.00"));
        String poor = card(Money.of("1.00"));
        insurance.failNextCancels(1);
        assertThatThrownBy(() -> checkout.checkout(order.id(), new CheckoutDTO(List.of(
                Tender.campusCard(Money.of("4.00"), rich), Tender.campusCard(Money.of("4.00"), poor)), null, null),
                UUID.randomUUID().toString(), CASHIER))
                .isInstanceOf(BizException.class).hasMessageContaining("医保结算未能撤销");

        // 校园卡已冲正，但医保结算仍有效：不能记为已撤回，转为待财务处理
        assertThat(campusCard.balance(rich)).isEqualTo(Money.of("100.00"));
        assertThat(insurance.hasSettlement(order.chargeNo())).isTrue();
        assertThat(rows(order.id())).singleElement()
                .satisfies(r -> assertThat(r.getStatus()).isEqualTo(InsuranceSettlement.SETTLED));
        CounterCheckout ex = checkoutMapper.selectBlocking(order.id());
        assertThat(ex.getStatus()).isEqualTo(CounterCheckout.NEEDS_ATTENTION);
        assertThat(ex.getFailReason()).contains("医保结算未能撤销");

        // 确认前不能重复收费、不能作废
        assertThatThrownBy(() -> checkout.cashPay(order.id(), order.payableAmount(),
                UUID.randomUUID().toString(), CASHIER))
                .satisfies(e -> assertThat(((BizException) e).getErrorCode())
                        .isEqualTo(ChargeErrorCode.CHECKOUT_NEEDS_ATTENTION));
        assertThatThrownBy(() -> chargeOrderService.cancel(order.id()))
                .satisfies(e -> assertThat(((BizException) e).getErrorCode())
                        .isEqualTo(ChargeErrorCode.CHECKOUT_NEEDS_ATTENTION));

        // 医保中心仍不可用：财务不能直接标记已处理
        insurance.failNextCancels(2);
        assertThat(checkout.retryInsuranceCancel(ex.getId())).isFalse();
        assertThatThrownBy(() -> checkout.resolve(ex.getId(), "已核对", false, 5L))
                .satisfies(e -> assertThat(((BizException) e).getErrorCode())
                        .isEqualTo(ChargeErrorCode.INSURANCE_CANCEL_PENDING));
        // 医保中心恢复后重试撤销成功，再标记已处理；收费单可以重新收费，业务号换新
        assertThat(checkout.retryInsuranceCancel(ex.getId())).isTrue();
        assertThat(insurance.hasSettlement(order.chargeNo())).isFalse();
        checkout.resolve(ex.getId(), "医保中心恢复后已撤销；校园卡已冲正", false, 5L);
        checkout.cashPay(order.id(), order.payableAmount(), UUID.randomUUID().toString(), CASHIER);
        assertThat(settled(order.id())).singleElement()
                .satisfies(r -> assertThat(r.getBizNo()).isEqualTo(order.chargeNo() + "-2"));
    }

    @Test
    void financeCanRecordAManualInsuranceCancelWhenTheCenterKeepsFailing() {
        ChargeOrderVO order = newOrder("JC-XCG", 1);   // 自付 4.00
        String poor = card(Money.of("1.00"));
        insurance.failNextCancels(100);
        assertThatThrownBy(() -> checkout.checkout(order.id(), new CheckoutDTO(List.of(
                Tender.campusCard(Money.of("4.00"), poor)), null, null), UUID.randomUUID().toString(), CASHIER))
                .isInstanceOf(BizException.class);
        CounterCheckout ex = checkoutMapper.selectBlocking(order.id());
        checkout.resolve(ex.getId(), "已在医保经办机构手工撤销，回执号 HX001", true, 5L);
        InsuranceSettlement row = rows(order.id()).getFirst();
        assertThat(row.getStatus()).isEqualTo(InsuranceSettlement.CANCELLED);
        assertThat(row.getCancelBy()).isEqualTo(5L);
        assertThat(row.getCancelNote()).contains("HX001");
        assertThat(checkoutMapper.selectBlocking(order.id())).isNull();
        // 测试替身里这笔仍在（相当于医保端已手工处理），清理掉避免影响对账用例
        insurance.failNextCancels(0);
        insurance.forget(order.chargeNo());
    }

    @Test
    void reconciliationWithTheInsuranceCenterFindsMissingAndForeignSettlements() {
        List<ChargeOrderVO> orders = List.of(cashPaid("JC-XCG", 1), cashPaid("JC-XCG", 1), cashPaid("JC-XCG", 1));
        LocalDate today = LocalDate.now(clock);
        String lost = orders.get(0).chargeNo();
        insurance.forget(lost);
        String foreign = "FOREIGN" + UUID.randomUUID().toString().substring(0, 8);
        insurance.injectForeign(foreign, Money.of("16.00"), LocalDateTime.now(clock));

        reconService.reconcile(today);
        reconService.reconcile(today);

        long shorts = diffMapper.selectCount(Wrappers.<ReconDiff>lambdaQuery().eq(ReconDiff::getReconDate, today)
                .eq(ReconDiff::getChannel, ChannelBillPort.INSURANCE).eq(ReconDiff::getPayNo, lost)
                .eq(ReconDiff::getDiffType, 2));
        long longs = diffMapper.selectCount(Wrappers.<ReconDiff>lambdaQuery().eq(ReconDiff::getReconDate, today)
                .eq(ReconDiff::getChannel, ChannelBillPort.INSURANCE).eq(ReconDiff::getPayNo, foreign)
                .eq(ReconDiff::getDiffType, 1));
        long falsePositives = diffMapper.selectCount(Wrappers.<ReconDiff>lambdaQuery()
                .eq(ReconDiff::getReconDate, today).eq(ReconDiff::getChannel, ChannelBillPort.INSURANCE)
                .in(ReconDiff::getPayNo, orders.get(1).chargeNo(), orders.get(2).chargeNo()));
        System.out.printf("[M25] recon: short=%d long=%d falsePositives=%d%n", shorts, longs, falsePositives);
        assertThat(shorts).isEqualTo(1);
        assertThat(longs).isEqualTo(1);
        assertThat(falsePositives).isZero();
        Settlement center = insurance.statement(today).stream().filter(s -> s.bizNo().equals(orders.get(1).chargeNo()))
                .findFirst().orElseThrow();
        assertThat(center.fundPay()).isEqualTo(Money.of("16.00"));
    }

    // ------------------------------------------------------------------ 辅助方法

    private String card(Money balance) {
        String cardNo = "2027" + (100000 + ThreadLocalRandom.current().nextInt(80000) * 10 + 1);
        campusCard.setBalance(cardNo, balance);
        return cardNo;
    }

    private ChargeOrderVO newOrder(String itemCode, int qty) {
        return chargeOrderService.createCounterOrder(
                new ChargeCreateDTO(STUDENT, List.of(new ChargeItemDTO(itemCode, qty))));
    }

    private ChargeOrderVO cashPaid(String itemCode, int qty) {
        ChargeOrderVO order = newOrder(itemCode, qty);
        checkout.cashPay(order.id(), order.payableAmount(), UUID.randomUUID().toString(), CASHIER);
        return order;
    }

    private RefundVO refund(Long orderId, int qty) {
        Long itemId = itemMapper.selectList(Wrappers.<ChargeOrderItem>lambdaQuery()
                .eq(ChargeOrderItem::getChargeOrderId, orderId)).getFirst().getId();
        RefundVO r = refundService.apply(orderId, new RefundApplyDTO(List.of(new RefundApplyDTO.Line(itemId, qty)),
                "测试退费"), UUID.randomUUID().toString(), CASHIER);
        confirmCash(r.id());
        return refundService.listByOrder(orderId).stream().filter(x -> x.id().equals(r.id())).findFirst()
                .orElseThrow();
    }

    /** 收费员当面退还现金后确认 */
    private void confirmCash(Long refundId) {
        for (RefundTender t : refundTenderMapper.selectByRefund(refundId)) {
            if (t.getStatus() == RefundTender.AWAITING_CONFIRM) {
                refundService.confirmTender(refundId, t.getId(), null, null, CASHIER);
            }
        }
    }

    private List<InsuranceSettlement> rows(Long orderId) {
        return settlementMapper.selectList(Wrappers.<InsuranceSettlement>lambdaQuery()
                .eq(InsuranceSettlement::getChargeOrderId, orderId).orderByAsc(InsuranceSettlement::getId));
    }

    private List<InsuranceSettlement> settled(Long orderId) {
        return rows(orderId).stream().filter(r -> r.getStatus() == InsuranceSettlement.SETTLED).toList();
    }

    private long successfulPays(Long orderId) {
        return payOrderMapper.selectCount(Wrappers.<PayOrder>lambdaQuery().eq(PayOrder::getChargeOrderId, orderId)
                .eq(PayOrder::getStatus, 1));
    }
}
