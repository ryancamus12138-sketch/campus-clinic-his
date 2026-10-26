package io.github.ryancamus.clinic.finance;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import io.github.ryancamus.clinic.charge.api.ChargeApi;
import io.github.ryancamus.clinic.charge.domain.dto.ChargeCreateDTO;
import io.github.ryancamus.clinic.charge.domain.dto.ChargeItemDTO;
import io.github.ryancamus.clinic.charge.domain.dto.CheckoutDTO;
import io.github.ryancamus.clinic.charge.domain.dto.RefundApplyDTO;
import io.github.ryancamus.clinic.charge.domain.entity.ChargeOrderItem;
import io.github.ryancamus.clinic.charge.domain.entity.RefundTender;
import io.github.ryancamus.clinic.charge.domain.vo.ChargeOrderVO;
import io.github.ryancamus.clinic.charge.domain.vo.RefundVO;
import io.github.ryancamus.clinic.charge.enums.ChargeStatus;
import io.github.ryancamus.clinic.charge.mapper.ChargeOrderItemMapper;
import io.github.ryancamus.clinic.charge.mapper.ChargeOrderMapper;
import io.github.ryancamus.clinic.charge.pay.mock.MockPayGateway;
import io.github.ryancamus.clinic.charge.service.ChargeOrderService;
import io.github.ryancamus.clinic.charge.service.CounterCheckoutService;
import io.github.ryancamus.clinic.charge.service.RefundService;
import io.github.ryancamus.clinic.common.money.Money;
import io.github.ryancamus.clinic.event.domain.entity.OutboxMessage;
import io.github.ryancamus.clinic.event.mapper.OutboxMessageMapper;
import io.github.ryancamus.clinic.event.service.OutboxService;
import io.github.ryancamus.clinic.finance.domain.entity.CashierShift;
import io.github.ryancamus.clinic.finance.domain.entity.EInvoice;
import io.github.ryancamus.clinic.finance.domain.entity.ReconDiff;
import io.github.ryancamus.clinic.finance.domain.vo.ReportVO;
import io.github.ryancamus.clinic.finance.domain.vo.ShiftVO;
import io.github.ryancamus.clinic.finance.invoice.MockFiscalInvoiceClient;
import io.github.ryancamus.clinic.finance.mapper.CashierShiftMapper;
import io.github.ryancamus.clinic.finance.mapper.EInvoiceMapper;
import io.github.ryancamus.clinic.finance.mapper.ReconDiffMapper;
import io.github.ryancamus.clinic.finance.service.ReconService;
import io.github.ryancamus.clinic.finance.service.ReportService;
import io.github.ryancamus.clinic.finance.service.ShiftService;
import io.github.ryancamus.clinic.support.IntegrationTest;
import io.github.ryancamus.clinic.support.MutableClock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@IntegrationTest
class FinanceIntegrationTest {

    private static final long STUDENT = 90001L;
    private static final long STAFF = 90002L;
    private static final long CASHIER = 2L;
    private static final int WECHAT = 4;
    private static final String WECHAT_CODE = "134567890123456789";

    @Autowired
    private MutableClock clock;
    @Autowired
    private ChargeOrderService chargeOrderService;
    @Autowired
    private CounterCheckoutService checkout;
    @Autowired
    private RefundService refundService;
    @Autowired
    private ShiftService shiftService;
    @Autowired
    private ReportService reportService;
    @Autowired
    private ReconService reconService;
    @Autowired
    private OutboxService outboxService;
    @Autowired
    private MockFiscalInvoiceClient fiscal;
    @Autowired
    private MockPayGateway gateway;
    @Autowired
    private ChargeOrderMapper orderMapper;
    @Autowired
    private ChargeOrderItemMapper itemMapper;
    @Autowired
    private CashierShiftMapper shiftMapper;
    @Autowired
    private EInvoiceMapper invoiceMapper;
    @Autowired
    private ReconDiffMapper diffMapper;
    @Autowired
    private OutboxMessageMapper outboxMapper;

    @AfterEach
    void restore() {
        clock.reset();
        fiscal.setFailRate(0);
    }

    // ------------------------------------------------------------------ 交班

    @Test
    void handOverSumsTheShiftFromLedgerAndShowsCashDifference() {
        closeOpenShift();
        ChargeOrderVO a = cashPaid(STAFF, "JC-XCG", 1);   // 20.00
        ChargeOrderVO b = cashPaid(STAFF, "JC-NCG", 2);   // 16.00
        ShiftVO current = shiftService.current(CASHIER).orElseThrow();
        assertThat(current.cashAmount()).isEqualByComparingTo("36.00");
        assertThat(current.orderCount()).isEqualTo(2);

        // 退一项现金：钱箱应有减少
        refundCash(b.id(), new RefundApplyDTO(List.of(new RefundApplyDTO.Line(firstItem(b.id()), 1)), "退一项"),
                UUID.randomUUID().toString(), CASHIER);
        ShiftVO closed = shiftService.handOver(current.id(), CASHIER, new BigDecimal("29.00"), shiftService.posExpected(current.id()), "交班核对");

        assertThat(closed.status()).isEqualTo(CashierShift.HANDED_OVER);
        assertThat(closed.cashAmount()).isEqualByComparingTo("36.00");
        assertThat(closed.refundAmount()).isEqualByComparingTo("8.00");
        assertThat(closed.expectedCash()).isEqualByComparingTo("28.00");
        assertThat(closed.cashDiff()).isEqualByComparingTo("1.00");
        // 交班后的收费自动进入新班次
        cashPaid(STAFF, "JC-NCG", 1);
        assertThat(shiftService.current(CASHIER).orElseThrow().id()).isNotEqualTo(current.id());
        assertThat(a.payableAmount()).isEqualTo(Money.of("20.00"));
    }

    // ------------------------------------------------------------------ M14

    /**
     * M14：D 日收费并交班、生成日报；D+1 日退费。D 日日报与已交班金额不变，退费记在 D+1 和新班次。
     */
    @Test
    void refundOnTheNextDayDoesNotChangeYesterdaysReportOrClosedShift() {
        LocalDate d = LocalDate.now(clock).plusDays(60);
        clock.travelTo(d.atTime(10, 0));
        closeOpenShift();
        ChargeOrderVO order = cashPaid(STAFF, "JC-XD", 2);   // 50.00
        Long shiftA = shiftService.current(CASHIER).orElseThrow().id();
        ShiftVO closedA = shiftService.handOver(shiftA, CASHIER, new BigDecimal("50.00"), shiftService.posExpected(shiftA), "交班核对");
        reportService.rebuildDay(d);
        ReportVO dayBefore = reportService.summary(d, d, null, false);

        clock.travelTo(d.plusDays(1).atTime(9, 30));
        refundCash(order.id(), new RefundApplyDTO(List.of(new RefundApplyDTO.Line(firstItem(order.id()), 1)),
                "次日退一项"), UUID.randomUUID().toString(), CASHIER);
        reportService.rebuildDay(d);
        reportService.rebuildDay(d.plusDays(1));

        ReportVO dayAfter = reportService.summary(d, d, null, false);
        ReportVO nextDay = reportService.summary(d.plusDays(1), d.plusDays(1), null, false);
        ShiftVO shiftB = shiftService.current(CASHIER).orElseThrow();
        CashierShift reloadedA = shiftMapper.selectById(shiftA);

        System.out.printf("[M14] dayD self=%s refund=%s -> self=%s refund=%s; dayD+1 refund=%s; shiftA expected=%s"
                        + " -> %s; shiftB refund=%s%n", dayBefore.total().selfAmount(), dayBefore.total().refundAmount(),
                dayAfter.total().selfAmount(), dayAfter.total().refundAmount(), nextDay.total().refundAmount(),
                closedA.expectedCash(), reloadedA.getExpectedCash(), shiftB.refundAmount());
        assertThat(dayAfter.total()).isEqualTo(dayBefore.total());
        assertThat(dayAfter.byItemType()).isEqualTo(dayBefore.byItemType());
        assertThat(reloadedA.getExpectedCash()).isEqualByComparingTo(closedA.expectedCash());
        assertThat(reloadedA.getRefundAmount().amount()).isEqualByComparingTo("0.00");
        assertThat(nextDay.total().refundAmount()).isEqualByComparingTo("25.00");
        assertThat(shiftB.id()).isNotEqualTo(shiftA);
        assertThat(shiftB.refundAmount()).isEqualByComparingTo("25.00");
        // 预聚合与直接扫流水的结果一致
        assertThat(reportService.summary(d, d.plusDays(1), null, true).total())
                .isEqualTo(reportService.summary(d, d.plusDays(1), null, false).total());
    }

    // ------------------------------------------------------------------ M13 电子票据

    /**
     * M13：票据平台随机失败 10%，发件箱重试后每张收费单恰好开出 1 张正票，重复开票 0。
     */
    @Test
    void invoicesAreIssuedExactlyOnceDespiteRandomPlatformFailures() {
        fiscal.setFailRate(0.1);
        long callsBefore = fiscal.calls();
        List<Long> orders = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            orders.add(cashPaid(STUDENT, "JC-NCG", 1).id());
        }
        int rounds = drainOutbox();

        int issued = 0;
        Set<String> invoiceNos = new HashSet<>();
        for (Long id : orders) {
            List<EInvoice> list = invoiceMapper.selectList(Wrappers.<EInvoice>lambdaQuery()
                    .eq(EInvoice::getChargeOrderId, id));
            assertThat(list).hasSize(1);
            assertThat(list.getFirst().getStatus()).isEqualTo(EInvoice.ISSUED);
            assertThat(list.getFirst().getAmount()).isEqualTo(Money.of("8.00"));
            invoiceNos.add(list.getFirst().getInvoiceNo());
            issued++;
        }
        System.out.printf("[M13] orders=100 issued=%d distinctInvoiceNo=%d platformCalls=%d rounds=%d dead=%d%n",
                issued, invoiceNos.size(), fiscal.calls() - callsBefore, rounds,
                outboxMapper.countByStatus(OutboxMessage.DEAD));
        assertThat(invoiceNos).hasSize(100);
        assertThat(fiscal.calls() - callsBefore).as("确实发生过失败重试").isGreaterThan(100);
        assertThat(outboxMapper.countByStatus(OutboxMessage.DEAD)).isZero();
    }

    @Test
    void partialRefundFlushesTheInvoiceRedAndReissuesTheRemainder() {
        ChargeOrderVO order = cashPaid(STUDENT, "ZL-JRZS", 3);   // 12.00，统筹 9.60，自付 2.40
        drainOutbox();
        Long itemId = firstItem(order.id());
        refundCash(order.id(), new RefundApplyDTO(List.of(new RefundApplyDTO.Line(itemId, 1)), "退 1 次"),
                UUID.randomUUID().toString(), CASHIER);
        drainOutbox();

        List<EInvoice> invoices = invoices(order.id());
        assertThat(invoices).extracting(EInvoice::getInvoiceType).containsExactly(1, 2, 1);
        EInvoice original = invoices.get(0);
        EInvoice red = invoices.get(1);
        EInvoice reissued = invoices.get(2);
        assertThat(red.getOriginalInvoiceId()).isEqualTo(original.getId());
        assertThat(red.getAmount()).isEqualTo(Money.of("12.00"));
        assertThat(reissued.getAmount()).isEqualTo(Money.of("8.00"));
        assertThat(reissued.getSelfAmount()).isEqualTo(Money.of("1.60"));
        assertThat(reissued.getInsuranceAmount()).isEqualTo(Money.of("6.40"));
        assertThat(invoices).allMatch(i -> i.getStatus() == EInvoice.ISSUED);

        // 再把剩余 2 次全部退掉：只冲红，不再重开
        refundCash(order.id(), new RefundApplyDTO(List.of(new RefundApplyDTO.Line(itemId, 2)), "全退"),
                UUID.randomUUID().toString(), CASHIER);
        drainOutbox();
        assertThat(invoices(order.id())).extracting(EInvoice::getInvoiceType).containsExactly(1, 2, 1, 2);
        assertThat(orderMapper.selectById(order.id()).getStatus()).isEqualTo(ChargeStatus.REFUNDED.code());
    }

    // ------------------------------------------------------------------ M9 渠道对账

    /**
     * M9：在付款码渠道测试替身的账单中注入 20 笔差异（长款 7、短款 7、金额不符 6），对账全部识别，正常交易无误报。
     */
    @Test
    void reconciliationFindsEveryInjectedDifference() throws Exception {
        List<String> payNos = new ArrayList<>();
        List<Long> orders = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            ChargeOrderVO order = chargeOrderService.createCounterOrder(
                    new ChargeCreateDTO(STUDENT, List.of(new ChargeItemDTO("JC-NCG", 1))));
            // 窗口扫微信付款码收自付部分，次日与微信（统一支付平台）账单对账
            String payNo = checkout.checkout(order.id(), new CheckoutDTO(List.of(
                    CheckoutDTO.Tender.authCode(order.payableAmount(), WECHAT_CODE)), null, null),
                    UUID.randomUUID().toString(), CASHIER).tenders().getFirst().payNo();
            payNos.add(payNo);
            orders.add(order.id());
        }

        LocalDate today = LocalDate.now(clock);
        List<String> shorts = payNos.subList(0, 7);
        List<String> mismatches = payNos.subList(7, 13);
        List<String> normal = payNos.subList(13, 30);
        shorts.forEach(gateway::hideFromBill);
        mismatches.forEach(p -> gateway.overrideBillAmount(p, Money.of("0.01")));
        List<String> phantoms = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            String payNo = "PHANTOM" + UUID.randomUUID().toString().substring(0, 8);
            phantoms.add(payNo);
            gateway.injectBillTrade(WECHAT, payNo, Money.of("9.99"), LocalDateTime.now(clock));
        }

        var result = reconService.reconcile(today);
        // 重复对账不会重复登记
        reconService.reconcile(today);

        int found = 0;
        for (String p : shorts) {
            found += diffs(p, today, 2);
        }
        for (String p : mismatches) {
            found += diffs(p, today, 3);
        }
        for (String p : phantoms) {
            found += diffs(p, today, 1);
        }
        int falsePositives = 0;
        for (String p : normal) {
            falsePositives += diffMapper.selectCount(Wrappers.<ReconDiff>lambdaQuery()
                    .eq(ReconDiff::getPayNo, p).eq(ReconDiff::getReconDate, today)).intValue();
        }
        System.out.printf("[M9] injected=20 found=%d falsePositives=%d result=%s%n", found, falsePositives, result);
        assertThat(found).isEqualTo(20);
        assertThat(falsePositives).isZero();
    }

    /**
     * 科室核算与收费员工作量：按开单科室汇总（收费处直接划价单列），按办理班次的收费员汇总窗口收退费。
     */
    @Test
    void reportsByDepartmentAndCashier() {
        LocalDate today = LocalDate.now(clock);
        Money deptBefore = deptNet(101L, today);
        int cashierBefore = cashierCount(today);

        var rx = chargeOrderService.createForPrescription(new ChargeApi.PrescriptionCharge(IdWorker.getId(), null,
                STAFF, 1L, 101L, List.of(new ChargeApi.PrescriptionLine(IdWorker.getId(), null, "JC-XCG", 1))));
        checkout.cashPay(rx.id(), rx.payableAmount(), UUID.randomUUID().toString(), CASHIER);
        cashPaid(STAFF, "JC-NCG", 1);

        Money deptDelta = deptNet(101L, today).subtract(deptBefore);
        long workloadDelta = cashierCount(today) - cashierBefore;
        assertThat(deptDelta).isEqualTo(Money.of("20.00"));
        assertThat(reportService.byDept(today, today, null)).anyMatch(d -> d.deptId() == null);
        assertThat(workloadDelta).isEqualTo(2);
        System.out.printf("[M19] dept accounting: dept101 +%s; cashier workload +%d%n", deptDelta, workloadDelta);
    }

    private Money deptNet(Long deptId, LocalDate date) {
        return reportService.byDept(date, date, null).stream().filter(d -> deptId.equals(d.deptId()))
                .map(d -> d.netAmount()).findFirst().orElse(Money.ZERO);
    }

    private int cashierCount(LocalDate date) {
        return reportService.byCashier(date, date, null).stream().filter(c -> c.cashierId() == CASHIER)
                .mapToInt(c -> c.chargeCount()).sum();
    }

    // ------------------------------------------------------------------ 辅助方法

    private ChargeOrderVO cashPaid(long patientId, String itemCode, int qty) {
        ChargeOrderVO order = chargeOrderService.createCounterOrder(
                new ChargeCreateDTO(patientId, List.of(new ChargeItemDTO(itemCode, qty))));
        checkout.cashPay(order.id(), order.payableAmount(), UUID.randomUUID().toString(), CASHIER);
        return order;
    }

    private void closeOpenShift() {
        CashierShift open = shiftMapper.selectOpen(CASHIER);
        if (open != null) {
            shiftService.handOver(open.getId(), CASHIER, BigDecimal.ZERO, shiftService.posExpected(open.getId()), "交班核对");
        }
    }

    private Long firstItem(Long orderId) {
        return itemMapper.selectList(Wrappers.<ChargeOrderItem>lambdaQuery()
                .eq(ChargeOrderItem::getChargeOrderId, orderId)).getFirst().getId();
    }

    private List<EInvoice> invoices(Long orderId) {
        return invoiceMapper.selectList(Wrappers.<EInvoice>lambdaQuery()
                .eq(EInvoice::getChargeOrderId, orderId).orderByAsc(EInvoice::getId));
    }

    private int diffs(String payNo, LocalDate date, int type) {
        return diffMapper.selectCount(Wrappers.<ReconDiff>lambdaQuery().eq(ReconDiff::getPayNo, payNo)
                .eq(ReconDiff::getReconDate, date).eq(ReconDiff::getDiffType, type)).intValue();
    }

    /** 反复投递直到发件箱清空；每轮把“当前时间”往后推，跳过退避等待 */
    /** 退费并由收费员确认现金已当面退还 */
    private RefundVO refundCash(Long orderId, RefundApplyDTO dto,
                                                                         String key, Long cashierId) {
        var r = refundService.apply(orderId, dto, key, cashierId);
        for (var t : r.tenders()) {
            if (t.status() == RefundTender.AWAITING_CONFIRM) {
                refundService.confirmTender(r.id(), t.id(), null, null, cashierId);
            }
        }
        return r;
    }

    private int drainOutbox() {
        int round = 0;
        while (outboxService.pendingCount() > 0 && round < 60) {
            round++;
            outboxService.dispatchOnce(LocalDateTime.now(clock).plusDays(400L * round), 1_000);
        }
        assertThat(outboxService.pendingCount()).isZero();
        return round;
    }
}
