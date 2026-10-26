package io.github.ryancamus.clinic.charge;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import io.github.ryancamus.clinic.charge.domain.dto.ChargeCreateDTO;
import io.github.ryancamus.clinic.charge.domain.dto.ChargeItemDTO;
import io.github.ryancamus.clinic.charge.domain.dto.CheckoutDTO.InsuranceRegistration;
import io.github.ryancamus.clinic.charge.domain.dto.CheckoutDTO.Tender;
import io.github.ryancamus.clinic.charge.domain.dto.CheckoutDTO;
import io.github.ryancamus.clinic.charge.domain.dto.RefundApplyDTO;
import io.github.ryancamus.clinic.charge.domain.entity.ChargeOrderItem;
import io.github.ryancamus.clinic.charge.domain.entity.CounterCheckout;
import io.github.ryancamus.clinic.charge.domain.entity.InsuranceSettlement;
import io.github.ryancamus.clinic.charge.domain.entity.RefundTender;
import io.github.ryancamus.clinic.charge.domain.vo.ChargeOrderVO;
import io.github.ryancamus.clinic.charge.domain.vo.PlatformStatusVO;
import io.github.ryancamus.clinic.charge.domain.vo.RefundVO;
import io.github.ryancamus.clinic.charge.enums.ChargeErrorCode;
import io.github.ryancamus.clinic.charge.enums.ChargeStatus;
import io.github.ryancamus.clinic.charge.enums.PayChannel;
import io.github.ryancamus.clinic.charge.enums.RefundStatus;
import io.github.ryancamus.clinic.charge.mapper.ChargeOrderItemMapper;
import io.github.ryancamus.clinic.charge.mapper.ChargeOrderMapper;
import io.github.ryancamus.clinic.charge.mapper.CounterCheckoutMapper;
import io.github.ryancamus.clinic.charge.mapper.InsuranceSettlementMapper;
import io.github.ryancamus.clinic.charge.pay.PayChannelRegistry;
import io.github.ryancamus.clinic.charge.service.ChargeOrderService;
import io.github.ryancamus.clinic.charge.service.CounterCheckoutService;
import io.github.ryancamus.clinic.charge.service.RefundService;
import io.github.ryancamus.clinic.common.exception.BizException;
import io.github.ryancamus.clinic.common.money.Money;
import io.github.ryancamus.clinic.event.service.OutboxService;
import io.github.ryancamus.clinic.finance.domain.entity.EInvoice;
import io.github.ryancamus.clinic.finance.mapper.EInvoiceMapper;
import io.github.ryancamus.clinic.finance.service.InvoiceService;
import io.github.ryancamus.clinic.finance.service.ReconService;
import io.github.ryancamus.clinic.pharmacy.service.TraceUploadService;
import io.github.ryancamus.clinic.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 外部平台都未接入（正式部署的默认状态）：系统不伪造任何外部成功结果。
 * <ul>
 *   <li>窗口只能收现金、银行卡（POS），不能刷校园卡、扫付款码；</li>
 *   <li>医保：划价按本院配置估算；收费员在医保结算终端办理后登记结算单号与统筹金额，记录登记人；
 *       登记的金额与划价不一致时，终端上的结算需人工撤销，转为收款异常；退费需登记终端上的撤销与重结；</li>
 *   <li>电子票据保持“待开具”，财务开具后登记票号；追溯码只在本地保存，不上传。</li>
 * </ul>
 */
@IntegrationTest
@TestPropertySource(properties = {
        "clinic.test-doubles.pay=false",
        "clinic.test-doubles.campus-card=false",
        "clinic.test-doubles.insurance=false",
        "clinic.test-doubles.fiscal-invoice=false",
        "clinic.test-doubles.trace-upload=false"})
class UnconnectedPlatformsIntegrationTest {

    private static final long STUDENT = 90001L;
    private static final long STAFF = 90002L;
    private static final long CASHIER = 2L;
    private static final long FINANCE = 5L;

    @Autowired
    private CounterCheckoutService checkout;
    @Autowired
    private ChargeOrderService chargeOrderService;
    @Autowired
    private RefundService refundService;
    @Autowired
    private PayChannelRegistry channels;
    @Autowired
    private InvoiceService invoiceService;
    @Autowired
    private ReconService reconService;
    @Autowired
    private TraceUploadService traceUploadService;
    @Autowired
    private OutboxService outboxService;
    @Autowired
    private ChargeOrderMapper orderMapper;
    @Autowired
    private ChargeOrderItemMapper itemMapper;
    @Autowired
    private InsuranceSettlementMapper settlementMapper;
    @Autowired
    private CounterCheckoutMapper checkoutMapper;
    @Autowired
    private EInvoiceMapper invoiceMapper;
    @Autowired
    private Clock clock;

    @Test
    void onlyCashAndPosAreOfferedWithoutCampusCardOrAuthCode() {
        assertThat(checkout.platformStatus()).isEqualTo(new PlatformStatusVO(false, false, false));
        assertThat(checkout.availableMethods(1L)).containsExactly(CheckoutDTO.Method.CASH, CheckoutDTO.Method.BANK_CARD);
        assertThat(channels.find(PayChannel.WECHAT.code())).isEmpty();

        ChargeOrderVO order = newOrder(STAFF, "JC-XD", 1);   // 自费 25.00
        assertThatThrownBy(() -> checkout.checkout(order.id(), new CheckoutDTO(List.of(
                Tender.campusCard(Money.of("25.00"), "20260001")), null, null), key(), CASHIER))
                .satisfies(e -> assertThat(((BizException) e).getErrorCode())
                        .isEqualTo(ChargeErrorCode.CHANNEL_NOT_SUPPORTED));
        assertThatThrownBy(() -> checkout.checkout(order.id(), new CheckoutDTO(List.of(
                Tender.authCode(Money.of("25.00"), "134567890123456789")), null, null), key(), CASHIER))
                .isInstanceOf(BizException.class);

        // POS 刷卡如实登记参考号与收费员
        var r = checkout.checkout(order.id(), new CheckoutDTO(List.of(
                Tender.bankCard(Money.of("25.00"), "POS20261026777")), null, null), key(), CASHIER);
        assertThat(r.tenders().getFirst().tenderRef()).isEqualTo("POS20261026777");
        assertThat(orderMapper.selectById(order.id()).getCashierId()).isEqualTo(CASHIER);
    }

    @Test
    void insuranceIsSettledAtTheTerminalAndRegisteredByTheCashier() {
        // 学生血常规 ×2：40.00，按配置估算统筹 32.00、自付 8.00
        ChargeOrderVO order = newOrder(STUDENT, "JC-XCG", 2);
        assertThat(order.insuranceAmount()).isEqualTo(Money.of("32.00"));

        // 没有登记终端结算结果：不收钱，提示到医保结算终端办理
        assertThatThrownBy(() -> checkout.checkout(order.id(), cash("8.00", null), key(), CASHIER))
                .satisfies(e -> assertThat(((BizException) e).getErrorCode())
                        .isEqualTo(ChargeErrorCode.INSURANCE_NOT_CONNECTED));
        assertThat(settlementMapper.selectCount(Wrappers.<InsuranceSettlement>lambdaQuery()
                .eq(InsuranceSettlement::getChargeOrderId, order.id()))).isZero();

        var r = checkout.checkout(order.id(), cash("8.00", new InsuranceRegistration("YB2026102600001",
                Money.of("32.00"))), key(), CASHIER);
        assertThat(r.setlId()).isEqualTo("YB2026102600001");
        assertThat(orderMapper.selectById(order.id()).getStatus()).isEqualTo(ChargeStatus.PAID.code());
        InsuranceSettlement s = settlementMapper.selectOne(Wrappers.<InsuranceSettlement>lambdaQuery()
                .eq(InsuranceSettlement::getChargeOrderId, order.id()));
        assertThat(s.getSource()).isEqualTo(InsuranceSettlement.SOURCE_MANUAL);
        assertThat(s.getOperatorId()).isEqualTo(CASHIER);
        assertThat(s.getFundPay()).isEqualTo(Money.of("32.00"));

        // 退 1 次：系统不能自行撤销医保，等收费员在终端撤销原结算、按剩余项目重结后登记
        Long itemId = firstItem(order.id());
        RefundVO refund = refundService.apply(order.id(), new RefundApplyDTO(
                List.of(new RefundApplyDTO.Line(itemId, 1)), "少做一项"), key(), CASHIER);
        assertThat(refund.status()).isEqualTo(RefundStatus.REFUNDING);
        assertThat(refund.awaitingInsuranceRegistration()).isTrue();
        assertThat(refund.tenders()).isEmpty();
        assertThatThrownBy(() -> refundService.registerInsurance(refund.id(),
                new InsuranceRegistration("YB2026102600002", Money.of("30.00")), CASHIER))
                .satisfies(e -> assertThat(((BizException) e).getErrorCode())
                        .isEqualTo(ChargeErrorCode.INSURANCE_REGISTRATION_INVALID));
        RefundVO registered = refundService.registerInsurance(refund.id(),
                new InsuranceRegistration("YB2026102600002", Money.of("16.00")), CASHIER);
        assertThat(registered.awaitingInsuranceRegistration()).isFalse();
        List<InsuranceSettlement> rows = settlementMapper.selectList(Wrappers.<InsuranceSettlement>lambdaQuery()
                .eq(InsuranceSettlement::getChargeOrderId, order.id()).orderByAsc(InsuranceSettlement::getId));
        assertThat(rows).extracting(InsuranceSettlement::getStatus)
                .containsExactly(InsuranceSettlement.CANCELLED, InsuranceSettlement.SETTLED);
        assertThat(rows.getFirst().getCancelBy()).isEqualTo(CASHIER);
        assertThat(rows.getLast().getBizNo()).isEqualTo(refund.refundNo());

        // 现金待收费员确认
        assertThat(registered.tenders()).singleElement()
                .satisfies(t -> assertThat(t.status()).isEqualTo(RefundTender.AWAITING_CONFIRM));
        RefundVO done = refundService.confirmTender(refund.id(), registered.tenders().getFirst().id(), null, null,
                CASHIER);
        assertThat(done.status()).isEqualTo(RefundStatus.SUCCESS);
        assertThat(orderMapper.selectById(order.id()).getRefundedAmount()).isEqualTo(Money.of("4.00"));
    }

    @Test
    void registeredFundAmountDifferentFromPricingBecomesAnExceptionForFinance() {
        ChargeOrderVO order = newOrder(STUDENT, "JC-XCG", 1);   // 统筹 16.00、自付 4.00
        assertThatThrownBy(() -> checkout.checkout(order.id(), cash("4.00", new InsuranceRegistration(
                "YB2026102600009", Money.of("15.00"))), key(), CASHIER))
                .isInstanceOf(BizException.class).hasMessageContaining("医保终端撤销");
        // 终端上已经产生了结算，系统撤不了：保留记录，转收款异常，阻止再收费
        CounterCheckout ex = checkoutMapper.selectBlocking(order.id());
        assertThat(ex.getStatus()).isEqualTo(CounterCheckout.NEEDS_ATTENTION);
        assertThatThrownBy(() -> checkout.checkout(order.id(), cash("4.00", new InsuranceRegistration(
                "YB2026102600010", Money.of("16.00"))), key(), CASHIER))
                .satisfies(e -> assertThat(((BizException) e).getErrorCode())
                        .isEqualTo(ChargeErrorCode.CHECKOUT_NEEDS_ATTENTION));
        assertThatThrownBy(() -> checkout.resolve(ex.getId(), "已核对", false, FINANCE))
                .satisfies(e -> assertThat(((BizException) e).getErrorCode())
                        .isEqualTo(ChargeErrorCode.INSURANCE_CANCEL_PENDING));
        checkout.resolve(ex.getId(), "收费员已在医保终端撤销结算 YB2026102600009", true, FINANCE);

        checkout.checkout(order.id(), cash("4.00", new InsuranceRegistration("YB2026102600010",
                Money.of("16.00"))), key(), CASHIER);
        assertThat(orderMapper.selectById(order.id()).getStatus()).isEqualTo(ChargeStatus.PAID.code());
    }

    @Test
    void reconciliationSaysNothingWasCheckedWhenNoPlatformIsConnected() {
        ChargeOrderVO order = newOrder(STAFF, "JC-XD", 1);
        checkout.checkout(order.id(), cash("25.00", null), key(), CASHIER);
        var result = reconService.reconcile(LocalDate.now(clock));
        // 没有已接入的平台：不对账，也不能显示成“全部一致”
        assertThat(result.channels()).isEmpty();
        assertThat(result.ourCount()).isZero();
        assertThat(result.channelCount()).isZero();
    }

    @Test
    void invoicesWaitForManualIssueAndTraceCodesStayLocal() {
        ChargeOrderVO order = newOrder(STAFF, "JC-XD", 1);
        checkout.checkout(order.id(), cash("25.00", null), key(), CASHIER);
        drainOutbox();

        assertThat(invoiceService.connected()).isFalse();
        EInvoice inv = invoiceMapper.selectOne(Wrappers.<EInvoice>lambdaQuery()
                .eq(EInvoice::getChargeOrderId, order.id()));
        assertThat(inv.getStatus()).isEqualTo(EInvoice.PENDING);
        assertThat(inv.getInvoiceNo()).isNull();

        var issued = invoiceService.registerManual(inv.getId(), "3100260001-00012345", FINANCE);
        assertThat(issued.status()).isEqualTo(EInvoice.ISSUED);
        assertThat(issued.issueMode()).isEqualTo(2);
        assertThat(issued.issuedBy()).isEqualTo(FINANCE);
        assertThat(issued.invoiceNo()).isEqualTo("3100260001-00012345");

        assertThat(traceUploadService.connected()).isFalse();
        assertThat(traceUploadService.uploadPending(100)).isZero();
    }

    // ------------------------------------------------------------------ 辅助方法

    private static CheckoutDTO cash(String amount, InsuranceRegistration registration) {
        return new CheckoutDTO(List.of(Tender.cash(Money.of(amount), null)), null, null, registration);
    }

    private ChargeOrderVO newOrder(long patientId, String itemCode, int qty) {
        return chargeOrderService.createCounterOrder(
                new ChargeCreateDTO(patientId, List.of(new ChargeItemDTO(itemCode, qty))));
    }

    private Long firstItem(Long orderId) {
        return itemMapper.selectList(Wrappers.<ChargeOrderItem>lambdaQuery()
                .eq(ChargeOrderItem::getChargeOrderId, orderId)).getFirst().getId();
    }

    private void drainOutbox() {
        for (int round = 1; round <= 20 && outboxService.pendingCount() > 0; round++) {
            outboxService.dispatchOnce(LocalDateTime.now(clock).plusDays(400L * round), 1_000);
        }
        assertThat(outboxService.pendingCount()).isZero();
    }

    private static String key() {
        return UUID.randomUUID().toString();
    }
}
