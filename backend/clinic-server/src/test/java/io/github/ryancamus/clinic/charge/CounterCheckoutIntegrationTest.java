package io.github.ryancamus.clinic.charge;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import io.github.ryancamus.clinic.charge.campuscard.mock.MockCampusCardGateway;
import io.github.ryancamus.clinic.charge.domain.dto.ChargeCreateDTO;
import io.github.ryancamus.clinic.charge.domain.dto.ChargeItemDTO;
import io.github.ryancamus.clinic.charge.domain.dto.CheckoutDTO.Tender;
import io.github.ryancamus.clinic.charge.domain.dto.CheckoutDTO;
import io.github.ryancamus.clinic.charge.domain.dto.RefundApplyDTO;
import io.github.ryancamus.clinic.charge.domain.dto.TenderVerifyDTO;
import io.github.ryancamus.clinic.charge.domain.entity.ChargeOrderItem;
import io.github.ryancamus.clinic.charge.domain.entity.CounterCheckout;
import io.github.ryancamus.clinic.charge.domain.entity.CounterCheckoutTender;
import io.github.ryancamus.clinic.charge.domain.entity.InsuranceSettlement;
import io.github.ryancamus.clinic.charge.domain.entity.PayOrder;
import io.github.ryancamus.clinic.charge.domain.entity.RefundTender;
import io.github.ryancamus.clinic.charge.domain.vo.ChargeOrderVO;
import io.github.ryancamus.clinic.charge.domain.vo.CheckoutExceptionVO;
import io.github.ryancamus.clinic.charge.domain.vo.CheckoutResultVO;
import io.github.ryancamus.clinic.charge.domain.vo.RefundVO;
import io.github.ryancamus.clinic.charge.enums.ChargeErrorCode;
import io.github.ryancamus.clinic.charge.enums.ChargeStatus;
import io.github.ryancamus.clinic.charge.enums.PayChannel;
import io.github.ryancamus.clinic.charge.enums.RefundStatus;
import io.github.ryancamus.clinic.charge.insurance.mock.MockInsuranceGateway;
import io.github.ryancamus.clinic.charge.mapper.ChargeOrderItemMapper;
import io.github.ryancamus.clinic.charge.mapper.ChargeOrderMapper;
import io.github.ryancamus.clinic.charge.mapper.CounterCheckoutMapper;
import io.github.ryancamus.clinic.charge.mapper.InsuranceSettlementMapper;
import io.github.ryancamus.clinic.charge.mapper.PayOrderMapper;
import io.github.ryancamus.clinic.charge.mapper.RefundOrderMapper;
import io.github.ryancamus.clinic.charge.mapper.RefundTenderMapper;
import io.github.ryancamus.clinic.charge.pay.mock.MockPayGateway;
import io.github.ryancamus.clinic.charge.service.ChargeOrderService;
import io.github.ryancamus.clinic.charge.service.CounterCheckoutService;
import io.github.ryancamus.clinic.charge.service.RefundService;
import io.github.ryancamus.clinic.common.exception.BizException;
import io.github.ryancamus.clinic.common.money.Money;
import io.github.ryancamus.clinic.finance.domain.entity.FundLedger;
import io.github.ryancamus.clinic.finance.enums.FinanceErrorCode;
import io.github.ryancamus.clinic.finance.mapper.FundLedgerMapper;
import io.github.ryancamus.clinic.finance.service.ShiftService;
import io.github.ryancamus.clinic.support.ApiClient;
import io.github.ryancamus.clinic.support.IntegrationTest;
import io.github.ryancamus.clinic.support.MutableClock;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 收费窗口结算：医保同步结算 + 现金、校园卡、银行卡、付款码组合收取自付；失败整体撤回；退费按原方式逆序退回。
 */
@IntegrationTest
class CounterCheckoutIntegrationTest {

    private static final long STUDENT = 90001L;
    private static final long STAFF = 90002L;
    private static final long CASHIER = 2L;
    private static final long FINANCE = 5L;
    private static final String WECHAT_CODE = "134567890123456789";

    @Autowired
    private CounterCheckoutService checkout;
    @Autowired
    private ChargeOrderService chargeOrderService;
    @Autowired
    private RefundService refundService;
    @Autowired
    private MockCampusCardGateway campusCard;
    @Autowired
    private MockInsuranceGateway insurance;
    @Autowired
    private MockPayGateway payGateway;
    @Autowired
    private ChargeOrderMapper orderMapper;
    @Autowired
    private ChargeOrderItemMapper itemMapper;
    @Autowired
    private PayOrderMapper payOrderMapper;
    @Autowired
    private RefundTenderMapper refundTenderMapper;
    @Autowired
    private InsuranceSettlementMapper settlementMapper;
    @Autowired
    private FundLedgerMapper ledgerMapper;
    @Autowired
    private RefundOrderMapper refundMapper;
    @Autowired
    private ShiftService shiftService;
    @Autowired
    private CounterCheckoutMapper checkoutMapper;
    @Autowired
    private MutableClock clock;
    @Autowired
    private MockMvc mvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private StringRedisTemplate redis;

    @Test
    void campusCardPlusCashSettlesInsuranceFirstAndRecordsEachTender() {
        // 学生血常规 ×2：40.00，医保统筹 32.00，自付 8.00 = 校园卡 5.00 + 现金 3.00（收 5.00 找 2.00）
        ChargeOrderVO order = newOrder(STUDENT, "JC-XCG", 2);
        String card = card(Money.of("100.00"));

        CheckoutResultVO r = checkout.checkout(order.id(), new CheckoutDTO(List.of(
                Tender.campusCard(Money.of("5.00"), card),
                Tender.cash(Money.of("3.00"), Money.of("5.00"))), "01", "E-VOUCHER-TOKEN"), key(), CASHIER);

        assertThat(r.fundPay()).isEqualTo(Money.of("32.00"));
        assertThat(r.selfAmount()).isEqualTo(Money.of("8.00"));
        assertThat(r.setlId()).isNotBlank();
        assertThat(r.change()).isEqualTo(Money.of("2.00"));
        assertThat(r.tenders()).extracting(CheckoutResultVO.TenderVO::channel)
                .containsExactly(PayChannel.CAMPUS_CARD.code(), PayChannel.CASH.code());
        assertThat(r.tenders().getFirst().tenderRef()).endsWith(card.substring(card.length() - 4)).startsWith("*");
        assertThat(campusCard.balance(card)).isEqualTo(Money.of("95.00"));
        assertThat(orderMapper.selectById(order.id()).getStatus()).isEqualTo(ChargeStatus.PAID.code());

        InsuranceSettlement s = settlementMapper.selectOne(Wrappers.<InsuranceSettlement>lambdaQuery()
                .eq(InsuranceSettlement::getChargeOrderId, order.id()));
        assertThat(s.getStatus()).isEqualTo(InsuranceSettlement.SETTLED);
        assertThat(s.getCertType()).isEqualTo("01");
        assertThat(insurance.hasSettlement(order.chargeNo())).isTrue();

        // 每种方式各记一笔流水，自付之和等于该方式金额；统筹记在第一种方式上，所有流水都在收费员当班
        List<FundLedger> cardRows = ledger(r.tenders().get(0).payNo());
        List<FundLedger> cashRows = ledger(r.tenders().get(1).payNo());
        assertThat(sum(cardRows, FundLedger::getAmount)).isEqualByComparingTo("5.00");
        assertThat(sum(cashRows, FundLedger::getAmount)).isEqualByComparingTo("3.00");
        assertThat(sum(cardRows, FundLedger::getInsuranceAmount)).isEqualByComparingTo("32.00");
        assertThat(cardRows).allMatch(l -> l.getChannel() == 2 && l.getShiftId() != null);
        assertThat(cashRows).allMatch(l -> l.getChannel() == 1 && l.getShiftId() != null);
    }

    @Test
    void insufficientCampusCardRollsBackEverythingCollectedSoFar() {
        ChargeOrderVO order = newOrder(STUDENT, "JC-XCG", 2);   // 自付 8.00
        String rich = card(Money.of("100.00"));
        String poor = card(Money.of("1.00"));

        assertThatThrownBy(() -> checkout.checkout(order.id(), new CheckoutDTO(List.of(
                Tender.campusCard(Money.of("4.00"), rich),
                Tender.campusCard(Money.of("4.00"), poor)), null, null), key(), CASHIER))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getErrorCode()).isEqualTo(ChargeErrorCode.TENDER_FAILED))
                .hasMessageContaining("余额不足");

        // 第一张卡已扣的 4.00 冲正，医保结算撤销，收费单仍待收费
        assertThat(campusCard.balance(rich)).isEqualTo(Money.of("100.00"));
        assertThat(campusCard.balance(poor)).isEqualTo(Money.of("1.00"));
        assertThat(insurance.hasSettlement(order.chargeNo())).isFalse();
        assertThat(settlementMapper.selectList(Wrappers.<InsuranceSettlement>lambdaQuery()
                .eq(InsuranceSettlement::getChargeOrderId, order.id())))
                .allMatch(x -> x.getStatus() == InsuranceSettlement.CANCELLED);
        assertThat(pays(order.id())).isEmpty();
        assertThat(orderMapper.selectById(order.id()).getStatus()).isEqualTo(ChargeStatus.UNPAID.code());

        // 换一种方式重收成功
        checkout.checkout(order.id(), new CheckoutDTO(List.of(Tender.cash(Money.of("8.00"), null)), null, null),
                key(), CASHIER);
        assertThat(orderMapper.selectById(order.id()).getStatus()).isEqualTo(ChargeStatus.PAID.code());
    }

    @Test
    void bankCardAndAuthCodeForASelfPayingPatient() {
        // 教工自费心电图 25.00 = POS 刷卡 10.00 + 微信付款码 15.00；无医保结算
        ChargeOrderVO order = newOrder(STAFF, "JC-XD", 1);
        CheckoutResultVO r = checkout.checkout(order.id(), new CheckoutDTO(List.of(
                Tender.bankCard(Money.of("10.00"), "POS20261026001"),
                Tender.authCode(Money.of("15.00"), WECHAT_CODE)), null, null), key(), CASHIER);

        assertThat(r.setlId()).isNull();
        assertThat(r.tenders()).extracting(CheckoutResultVO.TenderVO::channel)
                .containsExactly(PayChannel.BANK_CARD.code(), PayChannel.WECHAT.code());
        assertThat(r.tenders().get(0).tenderRef()).isEqualTo("POS20261026001");
        assertThat(r.tenders().get(1).channelTradeNo()).isNotBlank();
        assertThat(payGateway.query(r.tenders().get(1).payNo()).amount()).isEqualTo(Money.of("15.00"));
        // 付款码交易出现在微信账单中，次日对账能对上
        assertThat(payGateway.bill(PayChannel.WECHAT.code(), LocalDate.now()))
                .anyMatch(t -> t.payNo().equals(r.tenders().get(1).payNo()));
    }

    @Test
    void failedAuthCodeLeavesTheOrderUnpaid() {
        ChargeOrderVO order = newOrder(STAFF, "JC-XD", 1);
        payGateway.failNextMicropays(1);
        assertThatThrownBy(() -> checkout.checkout(order.id(), new CheckoutDTO(List.of(
                Tender.authCode(Money.of("25.00"), WECHAT_CODE)), null, null), key(), CASHIER))
                .isInstanceOf(BizException.class).hasMessageContaining("付款码");
        assertThat(pays(order.id())).isEmpty();
    }

    @Test
    void invalidTendersAreRejectedBeforeAnythingIsCollected() {
        ChargeOrderVO order = newOrder(STUDENT, "JC-XCG", 2);   // 自付 8.00
        assertThatThrownBy(() -> checkout.checkout(order.id(), new CheckoutDTO(List.of(
                Tender.cash(Money.of("5.00"), null)), null, null), key(), CASHIER))
                .satisfies(e -> assertThat(((BizException) e).getErrorCode())
                        .isEqualTo(ChargeErrorCode.TENDER_SUM_MISMATCH));
        assertThatThrownBy(() -> checkout.checkout(order.id(), new CheckoutDTO(List.of(
                Tender.authCode(Money.of("8.00"), "999999")), null, null), key(), CASHIER))
                .satisfies(e -> assertThat(((BizException) e).getErrorCode()).isEqualTo(ChargeErrorCode.TENDER_INVALID));
        assertThatThrownBy(() -> checkout.checkout(order.id(), new CheckoutDTO(List.of(
                Tender.bankCard(Money.of("8.00"), null)), null, null), key(), CASHIER))
                .satisfies(e -> assertThat(((BizException) e).getErrorCode()).isEqualTo(ChargeErrorCode.TENDER_INVALID));
        assertThatThrownBy(() -> checkout.checkout(order.id(), new CheckoutDTO(List.of(
                Tender.cash(Money.of("8.00"), null)), "09", null), key(), CASHIER))
                .satisfies(e -> assertThat(((BizException) e).getErrorCode()).isEqualTo(ChargeErrorCode.CERT_INVALID));
        assertThat(settlementMapper.selectCount(Wrappers.<InsuranceSettlement>lambdaQuery()
                .eq(InsuranceSettlement::getChargeOrderId, order.id()))).isZero();
    }

    @Test
    void sameIdempotencyKeyReturnsTheFirstResultWithoutChargingAgain() {
        ChargeOrderVO order = newOrder(STUDENT, "JC-XCG", 1);   // 自付 4.00
        String card = card(Money.of("50.00"));
        String key = key();
        CheckoutDTO dto = new CheckoutDTO(List.of(Tender.campusCard(Money.of("4.00"), card)), null, null);
        CheckoutResultVO first = checkout.checkout(order.id(), dto, key, CASHIER);
        CheckoutResultVO again = checkout.checkout(order.id(), dto, key, CASHIER);
        assertThat(again.tenders()).extracting(CheckoutResultVO.TenderVO::payNo)
                .containsExactlyElementsOf(first.tenders().stream().map(CheckoutResultVO.TenderVO::payNo).toList());
        assertThat(campusCard.balance(card)).isEqualTo(Money.of("46.00"));
    }

    @Test
    void refundGoesBackThroughEachTenderNewestFirst() {
        ChargeOrderVO order = newOrder(STUDENT, "JC-XCG", 2);
        String card = card(Money.of("100.00"));
        CheckoutResultVO paid = checkout.checkout(order.id(), new CheckoutDTO(List.of(
                Tender.campusCard(Money.of("5.00"), card),
                Tender.cash(Money.of("3.00"), null)), null, null), key(), CASHIER);

        // 退 1 次血常规：自付 4.00，先退后收的现金 3.00，再退校园卡 1.00；医保撤销后按剩余重结
        Long itemId = itemMapper.selectList(Wrappers.<ChargeOrderItem>lambdaQuery()
                .eq(ChargeOrderItem::getChargeOrderId, order.id())).getFirst().getId();
        RefundVO refund = refundService.apply(order.id(), new RefundApplyDTO(
                List.of(new RefundApplyDTO.Line(itemId, 1)), "少做一项"), key(), CASHIER);

        // 校园卡由平台原路退回；现金系统退不了，等收费员当面退还后确认，退费仍在“退款中”
        assertThat(refund.status()).isEqualTo(RefundStatus.REFUNDING);
        assertThat(refund.amount()).isEqualTo(Money.of("4.00"));
        List<RefundTender> tenders = refundTenderMapper.selectByRefund(refund.id());
        assertThat(tenders).extracting(RefundTender::getChannel).containsExactly(1, 2);
        assertThat(tenders).extracting(RefundTender::getAmount).containsExactly(Money.of("3.00"), Money.of("1.00"));
        assertThat(tenders).extracting(RefundTender::getStatus)
                .containsExactly(RefundTender.AWAITING_CONFIRM, RefundTender.SUCCESS);
        assertThat(campusCard.balance(card)).isEqualTo(Money.of("96.00"));
        assertThat(ledger(refund.refundNo())).isEmpty();
        // 定时重试也不会把现金自动记成已退
        refundService.retry(100);
        assertThat(refundTenderMapper.selectById(tenders.getFirst().getId()).getStatus())
                .isEqualTo(RefundTender.AWAITING_CONFIRM);

        // 另一位收费员（3 号）当面退还现金后确认：记录确认人、时间、班次，现金从他的钱箱付出
        long otherCashier = 3L;
        RefundVO done = refundService.confirmTender(refund.id(), tenders.getFirst().getId(), null, "患者签收",
                otherCashier);
        assertThat(done.status()).isEqualTo(RefundStatus.SUCCESS);
        RefundTender cash = refundTenderMapper.selectById(tenders.getFirst().getId());
        assertThat(cash.getStatus()).isEqualTo(RefundTender.SUCCESS);
        assertThat(cash.getConfirmBy()).isEqualTo(otherCashier);
        assertThat(cash.getConfirmTime()).isNotNull();
        assertThat(cash.getConfirmNote()).isEqualTo("患者签收");
        Long otherShift = shiftService.current(otherCashier).orElseThrow().id();
        assertThat(cash.getConfirmShiftId()).isEqualTo(otherShift);
        assertThatThrownBy(() -> refundService.confirmTender(refund.id(), tenders.getFirst().getId(), null, null,
                otherCashier)).satisfies(e -> assertThat(((BizException) e).getErrorCode())
                .isEqualTo(ChargeErrorCode.REFUND_TENDER_NOT_AWAITING));

        assertThat(payOrderMapper.selectByPayNo(paid.tenders().get(1).payNo()).getRefundedAmount())
                .isEqualTo(Money.of("3.00"));
        assertThat(ledger(refund.refundNo())).allMatch(l -> otherShift.equals(l.getShiftId()));
        assertThat(sum(ledger(refund.refundNo()), FundLedger::getAmount)).isEqualByComparingTo("-3.00");
        assertThat(sum(ledger(refund.refundNo() + "-2"), FundLedger::getAmount)).isEqualByComparingTo("-1.00");
        assertThat(settlementMapper.selectOne(Wrappers.<InsuranceSettlement>lambdaQuery()
                .eq(InsuranceSettlement::getBizNo, refund.refundNo())).getFundPay()).isEqualTo(Money.of("16.00"));

        // 交班：3 号收费员钱箱应有现金 −3.00，实点 0 元必须说明，说明后交班成功并留痕
        assertThatThrownBy(() -> shiftService.handOver(otherShift, otherCashier, new BigDecimal("0.00"), shiftService.posExpected(otherShift), null))
                .isInstanceOf(BizException.class).hasMessageContaining("长短款");
        var handed = shiftService.handOver(otherShift, otherCashier, new BigDecimal("0.00"), shiftService.posExpected(otherShift),
                "退费 3 元由备用金支付");
        assertThat(handed.expectedCash()).isEqualByComparingTo("-3.00");
        assertThat(handed.diffNote()).isEqualTo("退费 3 元由备用金支付");
        var reviewed = shiftService.review(otherShift, 5L, "已核对");
        assertThat(reviewed.reviewerId()).isEqualTo(5L);
        assertThat(reviewed.reviewTime()).isNotNull();
    }

    @Test
    void handoverReconcilesBankCardsWithThePosSlipAndCannotBeReviewedByTheCashier() {
        long cashier = 901L;   // 独立收费员，班次里只有这一笔
        ChargeOrderVO order = newOrder(STAFF, "JC-XD", 1);   // 自费 25.00
        checkout.checkout(order.id(), new CheckoutDTO(List.of(Tender.bankCard(Money.of("25.00"), "POS20261026777")),
                null, null), key(), cashier);
        Long shift = shiftService.current(cashier).orElseThrow().id();
        assertThat(shiftService.posExpected(shift)).isEqualByComparingTo("25.00");

        // 有银行卡收款：必须录入 POS 机结算单金额；与系统不一致必须说明
        assertThatThrownBy(() -> shiftService.handOver(shift, cashier, BigDecimal.ZERO, null, null))
                .satisfies(e -> assertThat(((BizException) e).getErrorCode())
                        .isEqualTo(FinanceErrorCode.POS_COUNT_REQUIRED));
        assertThatThrownBy(() -> shiftService.handOver(shift, cashier, BigDecimal.ZERO, new BigDecimal("20.00"), null))
                .satisfies(e -> assertThat(((BizException) e).getErrorCode())
                        .isEqualTo(FinanceErrorCode.DIFF_NOTE_REQUIRED));
        var handed = shiftService.handOver(shift, cashier, BigDecimal.ZERO, new BigDecimal("25.00"), null);
        assertThat(handed.posExpected()).isEqualByComparingTo("25.00");
        assertThat(handed.posCounted()).isEqualByComparingTo("25.00");
        assertThat(handed.posDiff()).isEqualByComparingTo("0.00");

        // 收费员不能复核自己的班次
        assertThatThrownBy(() -> shiftService.review(shift, cashier, "自己复核"))
                .satisfies(e -> assertThat(((BizException) e).getErrorCode())
                        .isEqualTo(FinanceErrorCode.REVIEWER_IS_CASHIER));
        assertThat(shiftService.review(shift, FINANCE, "POS 结算单与系统一致").reviewerId()).isEqualTo(FINANCE);
    }

    @Test
    void cardPaymentAndRefundThatCancelOutStillNeedThePosSlip() {
        long cashier = 902L;
        ChargeOrderVO order = newOrder(STAFF, "JC-XD", 1);   // 自费 25.00
        checkout.checkout(order.id(), new CheckoutDTO(List.of(Tender.bankCard(Money.of("25.00"), "POS20261026888")),
                null, null), key(), cashier);
        Long itemId = itemMapper.selectList(Wrappers.<ChargeOrderItem>lambdaQuery()
                .eq(ChargeOrderItem::getChargeOrderId, order.id())).getFirst().getId();
        RefundVO refund = refundService.apply(order.id(), new RefundApplyDTO(
                List.of(new RefundApplyDTO.Line(itemId, 1)), "未检查"), key(), cashier);
        refundService.confirmTender(refund.id(), refund.tenders().getFirst().id(), "POSRF20261026888", null, cashier);
        Long shift = shiftService.current(cashier).orElseThrow().id();
        // 收 25、退 25，净额为 0，但 POS 机上有两笔交易，仍要核对结算单
        assertThat(shiftService.posExpected(shift)).isEqualByComparingTo("0.00");
        assertThatThrownBy(() -> shiftService.handOver(shift, cashier, BigDecimal.ZERO, null, null))
                .satisfies(e -> assertThat(((BizException) e).getErrorCode())
                        .isEqualTo(FinanceErrorCode.POS_COUNT_REQUIRED));
        assertThat(shiftService.handOver(shift, cashier, BigDecimal.ZERO, new BigDecimal("0.00"), null).posCounted())
                .isEqualByComparingTo("0.00");
    }

    @Test
    void bankCardRefundNeedsThePosReference() {
        ChargeOrderVO order = newOrder(STAFF, "JC-XD", 1);   // 自费 25.00
        checkout.checkout(order.id(), new CheckoutDTO(List.of(Tender.bankCard(Money.of("25.00"), "POS20261026321")),
                null, null), key(), CASHIER);
        Long itemId = itemMapper.selectList(Wrappers.<ChargeOrderItem>lambdaQuery()
                .eq(ChargeOrderItem::getChargeOrderId, order.id())).getFirst().getId();
        RefundVO refund = refundService.apply(order.id(), new RefundApplyDTO(
                List.of(new RefundApplyDTO.Line(itemId, 1)), "未检查"), key(), CASHIER);
        assertThat(refund.status()).isEqualTo(RefundStatus.REFUNDING);
        RefundVO.Tender t = refund.tenders().getFirst();
        assertThat(t.status()).isEqualTo(RefundTender.AWAITING_CONFIRM);
        assertThatThrownBy(() -> refundService.confirmTender(refund.id(), t.id(), null, null, CASHIER))
                .satisfies(e -> assertThat(((BizException) e).getErrorCode())
                        .isEqualTo(ChargeErrorCode.REFUND_REFERENCE_REQUIRED));
        RefundVO done = refundService.confirmTender(refund.id(), t.id(), "POSRF20261026", null, CASHIER);
        assertThat(done.status()).isEqualTo(RefundStatus.SUCCESS);
        assertThat(done.tenders().getFirst().channelRefundNo()).isEqualTo("POSRF20261026");
        assertThat(done.tenders().getFirst().confirmBy()).isEqualTo(CASHIER);
    }

    @Test
    void refundApprovedAfterHandoverIsPaidFromTheCashiersCurrentShift() {
        // 教工心电图 ×10 = 250.00，超过 200 元需财务审批
        ChargeOrderVO order = newOrder(STAFF, "JC-XD", 10);
        checkout.checkout(order.id(), new CheckoutDTO(List.of(Tender.cash(Money.of("250.00"), null)), null, null),
                key(), CASHIER);
        Long itemId = itemMapper.selectList(Wrappers.<ChargeOrderItem>lambdaQuery()
                .eq(ChargeOrderItem::getChargeOrderId, order.id())).getFirst().getId();
        RefundVO pending = refundService.apply(order.id(), new RefundApplyDTO(
                List.of(new RefundApplyDTO.Line(itemId, 10)), "整单退"), key(), CASHIER);
        assertThat(pending.status()).isEqualTo(RefundStatus.PENDING_APPROVAL);
        Long applyShift = refundMapper.selectById(pending.id()).getShiftId();

        // 申请后收费员交班，第二天财务才审批；审批后收费员当面退还现金并确认：退款记入收费员现在的班次
        shiftService.handOver(applyShift, CASHIER, new BigDecimal("0.00"), shiftService.posExpected(applyShift), "交班核对");
        RefundVO approved = refundService.approve(pending.id(), 5L);
        assertThat(approved.status()).isEqualTo(RefundStatus.REFUNDING);
        refundService.confirmTender(pending.id(), approved.tenders().getFirst().id(), null, null, CASHIER);

        Long current = shiftService.current(CASHIER).orElseThrow().id();
        assertThat(current).isNotEqualTo(applyShift);
        assertThat(ledger(pending.refundNo())).isNotEmpty().allMatch(l -> current.equals(l.getShiftId()));
        // 新班次只有一笔 250 元现金退款（应有现金为负），交掉它，避免影响其他用例读取“当前班次”
        shiftService.handOver(current, CASHIER, new BigDecimal("0.00"), shiftService.posExpected(current), "交班核对");
    }

    @Test
    void concurrentWindowsCollectingTheSameOrderChargeOnlyOnce() throws Exception {
        // 教工自费心电图 25.00；20 个窗口同时用 20 张不同的校园卡收同一张单
        ChargeOrderVO order = newOrder(STAFF, "JC-XD", 1);
        List<String> cards = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            cards.add(card(Money.of("100.00")));
        }
        CountDownLatch start = new CountDownLatch(1);
        Map<String, Integer> outcomes = new ConcurrentHashMap<>();
        try (var pool = Executors.newFixedThreadPool(20)) {
            List<Future<?>> futures = new ArrayList<>();
            for (String c : cards) {
                futures.add(pool.submit(() -> {
                    start.await();
                    try {
                        checkout.checkout(order.id(), new CheckoutDTO(List.of(Tender.campusCard(Money.of("25.00"), c)),
                                null, null), key(), CASHIER);
                        outcomes.merge("SUCCESS", 1, Integer::sum);
                    } catch (BizException e) {
                        outcomes.merge(String.valueOf(e.getErrorCode().code()), 1, Integer::sum);
                    }
                    return null;
                }));
            }
            start.countDown();
            for (var f : futures) {
                f.get();
            }
        }
        Money charged = Money.ZERO;
        for (String c : cards) {
            charged = charged.add(Money.of("100.00").subtract(campusCard.balance(c)));
        }
        System.out.printf("[M26] concurrent windows=20 outcomes=%s charged=%s%n", outcomes, charged);
        assertThat(outcomes.get("SUCCESS")).isEqualTo(1);
        assertThat(charged).isEqualTo(Money.of("25.00"));
        assertThat(pays(order.id())).hasSize(1);
    }

    @Test
    void lockedOrderCannotBePaidElsewhereAndStaleCheckoutsGoToFinance() {
        ChargeOrderVO order = newOrder(STAFF, "JC-XD", 1);
        String card = card(Money.of("100.00"));
        // 模拟一个窗口在收款途中程序中断：留下一条“进行中”的结算
        CounterCheckout stuck = new CounterCheckout();
        stuck.setChargeOrderId(order.id());
        stuck.setCashierId(CASHIER);
        stuck.setIdempotencyKey("C2:stuck-" + key());
        stuck.setStatus(CounterCheckout.IN_PROGRESS);
        checkoutMapper.insert(stuck);

        assertThatThrownBy(() -> checkout.checkout(order.id(), new CheckoutDTO(List.of(
                Tender.campusCard(Money.of("25.00"), card)), null, null), key(), CASHIER))
                .satisfies(e -> assertThat(((BizException) e).getErrorCode())
                        .isEqualTo(ChargeErrorCode.CHECKOUT_IN_PROGRESS));
        assertThatThrownBy(() -> chargeOrderService.cancel(order.id())).isInstanceOf(BizException.class);
        assertThat(campusCard.balance(card)).isEqualTo(Money.of("100.00"));

        // 定时任务把中断的结算转给财务（待处理）
        // 拨到 10 分钟后：这条“进行中”已超过 5 分钟（DATETIME 秒级取整，不能用当前时刻比较）
        clock.travelTo(LocalDateTime.now(clock).plusMinutes(10));
        try {
            assertThat(checkout.markStale(Duration.ofMinutes(5), 100)).isPositive();
        } finally {
            clock.reset();
        }
        assertThat(checkout.exceptions(false)).anyMatch(x -> x.id().equals(stuck.getId()));
        // 待财务处理期间（扣款结果未知）：仍不能再收费或作废
        assertThatThrownBy(() -> checkout.checkout(order.id(), new CheckoutDTO(List.of(
                Tender.campusCard(Money.of("25.00"), card)), null, null), key(), CASHIER))
                .satisfies(e -> assertThat(((BizException) e).getErrorCode())
                        .isEqualTo(ChargeErrorCode.CHECKOUT_NEEDS_ATTENTION));
        assertThatThrownBy(() -> chargeOrderService.cancel(order.id()))
                .satisfies(e -> assertThat(((BizException) e).getErrorCode())
                        .isEqualTo(ChargeErrorCode.CHECKOUT_NEEDS_ATTENTION));
        assertThat(campusCard.balance(card)).isEqualTo(Money.of("100.00"));

        // 财务核对后登记处理结果（处理人、时间、说明），收费单解除锁定，可以正常收费
        checkout.resolve(stuck.getId(), "核对一卡通后台，未扣款", false, 5L);
        assertThat(checkout.exceptions(false)).noneMatch(x -> x.id().equals(stuck.getId()));
        var resolved = checkout.exceptions(true).stream().filter(x -> x.id().equals(stuck.getId())).findFirst()
                .orElseThrow();
        assertThat(resolved.resolverId()).isEqualTo(5L);
        assertThat(resolved.resolveNote()).isEqualTo("核对一卡通后台，未扣款");
        assertThat(resolved.resolveTime()).isNotNull();
        assertThatThrownBy(() -> checkout.resolve(stuck.getId(), "重复处理", false, 5L))
                .satisfies(e -> assertThat(((BizException) e).getErrorCode())
                        .isEqualTo(ChargeErrorCode.CHECKOUT_STATE_CHANGED));
        checkout.checkout(order.id(), new CheckoutDTO(List.of(Tender.campusCard(Money.of("25.00"), card)), null, null),
                key(), CASHIER);
        assertThat(campusCard.balance(card)).isEqualTo(Money.of("75.00"));
    }

    @Test
    void posPaymentThatCannotBeUndoneAutomaticallyIsHandedToFinance() {
        // 教工心电图 25.00 = POS 10.00 + 挂失的校园卡 15.00：校园卡失败，POS 交易系统撤不了，转人工
        ChargeOrderVO order = newOrder(STAFF, "JC-XD", 1);
        assertThatThrownBy(() -> checkout.checkout(order.id(), new CheckoutDTO(List.of(
                Tender.bankCard(Money.of("10.00"), "POS20261026999"),
                Tender.campusCard(Money.of("15.00"), "20260009")), null, null), key(), CASHIER))
                .hasMessageContaining("POS 机上撤销").hasMessageContaining("收款异常");
        var exception = checkout.exceptions(false).stream()
                .filter(x -> x.chargeNo().equals(order.chargeNo())).findFirst().orElseThrow();
        assertThat(exception.tenders()).extracting(CheckoutExceptionVO.Tender::status)
                .containsExactly(CounterCheckoutTender.REVERT_FAILED, CounterCheckoutTender.REVERTED);
        assertThat(exception.tenders()).extracting(CheckoutExceptionVO.Tender::needsVerification)
                .containsExactly(true, false);
        assertThat(pays(order.id())).isEmpty();

        // 不能只写一段说明就解除：POS 那一笔可能已扣款，必须先逐笔核实
        assertThatThrownBy(() -> checkout.resolve(exception.id(), "已核对", false, FINANCE))
                .satisfies(e -> assertThat(((BizException) e).getErrorCode())
                        .isEqualTo(ChargeErrorCode.TENDERS_NOT_VERIFIED));
        Long posTender = exception.tenders().getFirst().id();
        // 当笔收费的收费员本人不能核实、不能处理
        assertThatThrownBy(() -> checkout.verifyTender(exception.id(), posTender,
                new TenderVerifyDTO(TenderVerifyDTO.Result.RETURNED, "POSCX0001", null), CASHIER))
                .satisfies(e -> assertThat(((BizException) e).getErrorCode())
                        .isEqualTo(ChargeErrorCode.SAME_PERSON));
        // 银行卡必须有 POS 撤销参考号
        assertThatThrownBy(() -> checkout.verifyTender(exception.id(), posTender,
                new TenderVerifyDTO(TenderVerifyDTO.Result.RETURNED, null, "已撤销"), FINANCE))
                .satisfies(e -> assertThat(((BizException) e).getErrorCode())
                        .isEqualTo(ChargeErrorCode.VERIFY_REFERENCE_REQUIRED));
        checkout.verifyTender(exception.id(), posTender,
                new TenderVerifyDTO(TenderVerifyDTO.Result.RETURNED, "POSCX0001", "收费员已在 POS 机撤销当笔交易"), FINANCE);
        // 已核实的不能再改
        assertThatThrownBy(() -> checkout.verifyTender(exception.id(), posTender,
                new TenderVerifyDTO(TenderVerifyDTO.Result.NOT_CHARGED, "X0001", null), FINANCE))
                .satisfies(e -> assertThat(((BizException) e).getErrorCode())
                        .isEqualTo(ChargeErrorCode.CHECKOUT_STATE_CHANGED));
        assertThatThrownBy(() -> checkout.resolve(exception.id(), "已核对", false, CASHIER))
                .satisfies(e -> assertThat(((BizException) e).getErrorCode())
                        .isEqualTo(ChargeErrorCode.SAME_PERSON));
        checkout.resolve(exception.id(), "POS 已撤销，患者重新缴费", false, FINANCE);

        var done = checkout.exceptions(true).stream().filter(x -> x.id().equals(exception.id())).findFirst()
                .orElseThrow();
        CheckoutExceptionVO.Tender pos = done.tenders().getFirst();
        assertThat(pos.status()).isEqualTo(CounterCheckoutTender.VERIFIED_RETURNED);
        assertThat(pos.verifyBy()).isEqualTo(FINANCE);
        assertThat(pos.verifyRef()).isEqualTo("POSCX0001");
        assertThat(pos.verifyTime()).isNotNull();
        // 解除后可以重新收费：核实过的款项不计入任何班次，不会重复收取
        checkout.checkout(order.id(), new CheckoutDTO(List.of(Tender.cash(Money.of("25.00"), null)), null, null),
                key(), CASHIER);
        assertThat(pays(order.id())).singleElement()
                .satisfies(p -> assertThat(p.getChannel()).isEqualTo(PayChannel.CASH.code()));
    }

    @Test
    void cashTakenBeforeALaterTenderFailsStaysWithFinanceUntilItsReturnIsVerified() throws Exception {
        // 学生血常规 ×2：自付 8.00 = 现金 4.00（收 5.00）+ 余额不足的校园卡 4.00。现金先收，校园卡随后失败
        ChargeOrderVO order = newOrder(STUDENT, "JC-XCG", 2);
        String poor = card(Money.of("1.00"));
        String firstKey = key();
        assertThatThrownBy(() -> checkout.checkout(order.id(), new CheckoutDTO(List.of(
                Tender.cash(Money.of("4.00"), Money.of("5.00")),
                Tender.campusCard(Money.of("4.00"), poor)), null, null), firstKey, CASHIER))
                .satisfies(e -> assertThat(((BizException) e).getErrorCode()).isEqualTo(ChargeErrorCode.TENDER_FAILED))
                .hasMessageContaining("余额不足")
                .hasMessageContaining("已收现金须当面退还患者");

        // 现金不被当作已撤回：记为“现金待确认退还”，整次结算转收款异常；校园卡没有扣款，医保结算已撤销
        CheckoutExceptionVO exception = checkout.exceptions(false).stream()
                .filter(x -> x.chargeNo().equals(order.chargeNo())).findFirst().orElseThrow();
        assertThat(exception.failReason()).contains("已收现金须当面退还患者");
        assertThat(exception.tenders()).extracting(CheckoutExceptionVO.Tender::status)
                .containsExactly(CounterCheckoutTender.CASH_RETURN_UNCONFIRMED, CounterCheckoutTender.REVERTED);
        assertThat(exception.tenders()).extracting(CheckoutExceptionVO.Tender::needsVerification)
                .containsExactly(true, false);
        assertThat(campusCard.balance(poor)).isEqualTo(Money.of("1.00"));
        assertThat(insurance.hasSettlement(order.chargeNo())).isFalse();
        // 没有支付单、没有资金流水，收费单仍是未付
        assertThat(pays(order.id())).isEmpty();
        exception.tenders().forEach(t -> assertThat(ledger(t.payNo())).isEmpty());
        assertThat(orderMapper.selectById(order.id()).getStatus()).isEqualTo(ChargeStatus.UNPAID.code());

        // 现金退还未核实之前：原请求重试、换一个请求重收、作废都被拒绝，不会重复收款
        for (String k : List.of(firstKey, key())) {
            assertThatThrownBy(() -> checkout.checkout(order.id(), new CheckoutDTO(List.of(
                    Tender.cash(Money.of("8.00"), null)), null, null), k, CASHIER))
                    .satisfies(e -> assertThat(((BizException) e).getErrorCode())
                            .isEqualTo(ChargeErrorCode.CHECKOUT_NEEDS_ATTENTION));
        }
        assertThatThrownBy(() -> chargeOrderService.cancel(order.id()))
                .satisfies(e -> assertThat(((BizException) e).getErrorCode())
                        .isEqualTo(ChargeErrorCode.CHECKOUT_NEEDS_ATTENTION));
        assertThatThrownBy(() -> checkout.resolve(exception.id(), "已处理", false, FINANCE))
                .satisfies(e -> assertThat(((BizException) e).getErrorCode())
                        .isEqualTo(ChargeErrorCode.TENDERS_NOT_VERIFIED));

        Long cashTender = exception.tenders().getFirst().id();
        // 经手收费员本人不能核实；现金必须写明核实说明
        assertThatThrownBy(() -> checkout.verifyTender(exception.id(), cashTender,
                new TenderVerifyDTO(TenderVerifyDTO.Result.RETURNED, null, "已退还"), CASHIER))
                .satisfies(e -> assertThat(((BizException) e).getErrorCode()).isEqualTo(ChargeErrorCode.SAME_PERSON));
        assertThatThrownBy(() -> checkout.verifyTender(exception.id(), cashTender,
                new TenderVerifyDTO(TenderVerifyDTO.Result.RETURNED, null, null), FINANCE))
                .satisfies(e -> assertThat(((BizException) e).getErrorCode())
                        .isEqualTo(ChargeErrorCode.VERIFY_REFERENCE_REQUIRED));

        // 重复点击、多人同时核实同一笔现金：只成功一次
        Map<String, Integer> verifyOutcomes = concurrently(8, () -> checkout.verifyTender(exception.id(), cashTender,
                new TenderVerifyDTO(TenderVerifyDTO.Result.RETURNED, null, "收费员当面退还现金 4.00 元，患者签字"),
                FINANCE));
        assertThat(verifyOutcomes).containsEntry("SUCCESS", 1)
                .containsEntry(String.valueOf(ChargeErrorCode.CHECKOUT_STATE_CHANGED.code()), 7);

        // 同时登记处理结果：也只成功一次
        Map<String, Integer> resolveOutcomes = concurrently(8, () -> checkout.resolve(exception.id(),
                "现金已当面退还，患者重新缴费", false, FINANCE));
        assertThat(resolveOutcomes).containsEntry("SUCCESS", 1)
                .containsEntry(String.valueOf(ChargeErrorCode.CHECKOUT_STATE_CHANGED.code()), 7);

        CheckoutExceptionVO done = checkout.exceptions(true).stream()
                .filter(x -> x.id().equals(exception.id())).findFirst().orElseThrow();
        CheckoutExceptionVO.Tender cash = done.tenders().getFirst();
        assertThat(cash.status()).isEqualTo(CounterCheckoutTender.VERIFIED_RETURNED);
        assertThat(cash.verifyBy()).isEqualTo(FINANCE);
        assertThat(cash.verifyNote()).contains("当面退还");
        assertThat(done.resolverId()).isEqualTo(FINANCE);
        // 核实只记录钱的去向，不产生支付单和资金流水；收费单解除锁定，仍是未付
        assertThat(pays(order.id())).isEmpty();
        assertThat(orderMapper.selectById(order.id()).getStatus()).isEqualTo(ChargeStatus.UNPAID.code());

        // 重新收费：只有这一次登记为收款；同一请求重放返回同一结果，不再记账
        String secondKey = key();
        CheckoutDTO again = new CheckoutDTO(List.of(Tender.cash(Money.of("8.00"), Money.of("10.00"))), null, null);
        CheckoutResultVO paid = checkout.checkout(order.id(), again, secondKey, CASHIER);
        CheckoutResultVO replayed = checkout.checkout(order.id(), again, secondKey, CASHIER);
        assertThat(replayed.tenders()).extracting(CheckoutResultVO.TenderVO::payNo)
                .containsExactly(paid.tenders().getFirst().payNo());
        assertThat(orderMapper.selectById(order.id()).getStatus()).isEqualTo(ChargeStatus.PAID.code());
        assertThat(pays(order.id())).singleElement()
                .satisfies(p -> assertThat(p.getAmount()).isEqualTo(Money.of("8.00")));
        assertThat(sum(ledger(paid.tenders().getFirst().payNo()), FundLedger::getAmount)).isEqualByComparingTo("8.00");
    }

    @Test
    void checkoutEndpointRequiresTheSettlePermission() throws Exception {
        ApiClient api = new ApiClient(mvc, objectMapper, redis);
        String cashier = api.login("cashier01", ApiClient.STAFF_PASSWORD);
        String doctor = api.login("doctor01", ApiClient.STAFF_PASSWORD);
        ChargeOrderVO order = newOrder(STAFF, "JC-XD", 1);
        Map<String, Object> body = Map.of("tenders", List.of(
                Map.of("method", "CASH", "amount", "20.00", "received", "20.00"),
                Map.of("method", "BANK_CARD", "amount", "5.00", "reference", "POS0001")));

        post(doctor, order.id(), body).andExpect(status().isForbidden());
        api.get(cashier, "/api/charge/orders/tender-methods")
                .andExpect(jsonPath("$.data.length()").value(4))
                .andExpect(jsonPath("$.data[0]").value("CASH"));
        post(cashier, order.id(), body).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.tenders.length()").value(2))
                .andExpect(jsonPath("$.data.change").value("0.00"));
    }

    // ------------------------------------------------------------------ 辅助方法

    private ResultActions post(String token, Long orderId, Object body)
            throws Exception {
        return mvc.perform(MockMvcRequestBuilders.post("/api/charge/orders/" + orderId + "/checkout")
                .header("Authorization", token)
                .header("Idempotency-Key", key())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    /** 同时发起 n 次同一操作，按结果汇总：成功记 SUCCESS，业务失败记错误码 */
    private static Map<String, Integer> concurrently(int n, Runnable action) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        Map<String, Integer> outcomes = new ConcurrentHashMap<>();
        try (var pool = Executors.newFixedThreadPool(n)) {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    try {
                        action.run();
                        outcomes.merge("SUCCESS", 1, Integer::sum);
                    } catch (BizException e) {
                        outcomes.merge(String.valueOf(e.getErrorCode().code()), 1, Integer::sum);
                    }
                    return null;
                }));
            }
            start.countDown();
            for (var f : futures) {
                f.get();
            }
        }
        return outcomes;
    }

    private ChargeOrderVO newOrder(long patientId, String itemCode, int qty) {
        return chargeOrderService.createCounterOrder(
                new ChargeCreateDTO(patientId, List.of(new ChargeItemDTO(itemCode, qty))));
    }

    private String card(Money balance) {
        String cardNo = "2026" + (100000 + ThreadLocalRandom.current().nextInt(80000) * 10 + 1);
        campusCard.setBalance(cardNo, balance);
        return cardNo;
    }

    private static String key() {
        return UUID.randomUUID().toString();
    }

    private List<PayOrder> pays(Long orderId) {
        return payOrderMapper.selectList(Wrappers.<PayOrder>lambdaQuery().eq(PayOrder::getChargeOrderId, orderId));
    }

    private List<FundLedger> ledger(String bizNo) {
        return ledgerMapper.selectList(Wrappers.<FundLedger>lambdaQuery().eq(FundLedger::getBizNo, bizNo));
    }

    private static BigDecimal sum(List<FundLedger> rows, Function<FundLedger, BigDecimal> f) {
        return rows.stream().map(f).reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
