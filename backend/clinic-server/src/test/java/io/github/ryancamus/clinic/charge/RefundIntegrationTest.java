package io.github.ryancamus.clinic.charge;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import io.github.ryancamus.clinic.charge.domain.dto.ChargeCreateDTO;
import io.github.ryancamus.clinic.charge.domain.dto.ChargeItemDTO;
import io.github.ryancamus.clinic.charge.domain.dto.CheckoutDTO.Tender;
import io.github.ryancamus.clinic.charge.domain.dto.CheckoutDTO;
import io.github.ryancamus.clinic.charge.domain.dto.RefundApplyDTO;
import io.github.ryancamus.clinic.charge.domain.entity.ChargeOrder;
import io.github.ryancamus.clinic.charge.domain.entity.ChargeOrderItem;
import io.github.ryancamus.clinic.charge.domain.entity.RefundOrder;
import io.github.ryancamus.clinic.charge.domain.entity.RefundTender;
import io.github.ryancamus.clinic.charge.domain.vo.ChargeOrderVO;
import io.github.ryancamus.clinic.charge.domain.vo.PayResultVO;
import io.github.ryancamus.clinic.charge.domain.vo.RefundVO;
import io.github.ryancamus.clinic.charge.enums.ChargeErrorCode;
import io.github.ryancamus.clinic.charge.enums.ChargeStatus;
import io.github.ryancamus.clinic.charge.enums.RefundStatus;
import io.github.ryancamus.clinic.charge.mapper.ChargeOrderItemMapper;
import io.github.ryancamus.clinic.charge.mapper.ChargeOrderMapper;
import io.github.ryancamus.clinic.charge.mapper.RefundOrderMapper;
import io.github.ryancamus.clinic.charge.mapper.RefundTenderMapper;
import io.github.ryancamus.clinic.charge.pay.mock.MockPayGateway;
import io.github.ryancamus.clinic.charge.service.ChargeOrderService;
import io.github.ryancamus.clinic.charge.service.CounterCheckoutService;
import io.github.ryancamus.clinic.charge.service.RefundService;
import io.github.ryancamus.clinic.common.exception.BizException;
import io.github.ryancamus.clinic.common.money.Money;
import io.github.ryancamus.clinic.finance.domain.entity.FundLedger;
import io.github.ryancamus.clinic.finance.mapper.FundLedgerMapper;
import io.github.ryancamus.clinic.schedule.SlotFixtures;
import io.github.ryancamus.clinic.schedule.domain.entity.Registration;
import io.github.ryancamus.clinic.schedule.domain.entity.ScheduleSlot;
import io.github.ryancamus.clinic.schedule.enums.RegistrationStatus;
import io.github.ryancamus.clinic.schedule.enums.SlotType;
import io.github.ryancamus.clinic.schedule.mapper.RegistrationMapper;
import io.github.ryancamus.clinic.support.ApiClient;
import io.github.ryancamus.clinic.support.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@IntegrationTest
class RefundIntegrationTest {

    private static final long STUDENT = 90001L;
    private static final long STAFF = 90002L;
    private static final long CASHIER_ID = 2L;
    private static final long FINANCE_ID = 5L;
    private static final String WECHAT_CODE = "134567890123456789";

    @Autowired
    private MockMvc mvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private StringRedisTemplate redis;
    @Autowired
    private SlotFixtures slots;
    @Autowired
    private MockPayGateway gateway;
    @Autowired
    private ChargeOrderService chargeOrderService;
    @Autowired
    private CounterCheckoutService checkout;
    @Autowired
    private RefundService refundService;
    @Autowired
    private ChargeOrderMapper orderMapper;
    @Autowired
    private ChargeOrderItemMapper itemMapper;
    @Autowired
    private RefundOrderMapper refundMapper;
    @Autowired
    private RegistrationMapper registrationMapper;
    @Autowired
    private FundLedgerMapper ledgerMapper;
    @Autowired
    private RefundTenderMapper refundTenderMapper;

    private ApiClient api;
    private String doctor;
    private String cashier;
    private String finance;

    @BeforeEach
    void setUp() throws Exception {
        api = new ApiClient(mvc, objectMapper, redis);
        doctor = api.login("doctor01", ApiClient.STAFF_PASSWORD);
        cashier = api.login("cashier01", ApiClient.STAFF_PASSWORD);
        finance = api.login("finance01", ApiClient.STAFF_PASSWORD);
    }

    // ------------------------------------------------------------------ M5

    /**
     * M5：同一收费单并发 20 次全额退费申请，只有一笔成功，退款合计恰好等于实收。
     */
    @Test
    void twentyConcurrentFullRefundsRefundExactlyOnce() throws Exception {
        Paid paid = paidCashOrder(STUDENT, "ZL-JRZS", 3);
        ChargeOrderItem item = items(paid.orderId()).getFirst();

        Map<String, Integer> outcomes = race(20, () -> refundService.apply(paid.orderId(),
                new RefundApplyDTO(List.of(new RefundApplyDTO.Line(item.getId(), 3)), "并发全额退费"),
                UUID.randomUUID().toString(), CASHIER_ID));
        // 现金退款：收费员当面退还后确认
        confirmCash(paid.orderId());

        ChargeOrder order = orderMapper.selectById(paid.orderId());
        List<RefundOrder> succeeded = succeededRefunds(paid.orderId());
        System.out.printf("[M5] full: attempts=20 outcomes=%s paid=%s refunded=%s%n", outcomes,
                order.getPaidAmount(), order.getRefundedAmount());
        assertThat(succeeded).hasSize(1);
        assertThat(order.getRefundedAmount()).isEqualTo(order.getPaidAmount());
        assertThat(order.getStatus()).isEqualTo(ChargeStatus.REFUNDED.code());
        assertThat(itemMapper.selectById(item.getId()).getRefundedQty()).isEqualTo(3);
        assertThat(outcomes.keySet()).isSubsetOf("SUCCESS", "30015", "30016", "30017");
        // 收款流水 + 退款红冲流水 = 0
        assertThat(ledgerSum(paid.payNo()).add(ledgerSum(succeeded.getFirst().getRefundNo()))).isZero();
    }

    /**
     * M5：每次退 1 件、并发 20 次；之后补齐剩余。任何时刻退款合计不超过实收，退完恰好相等。
     */
    @Test
    void concurrentPartialRefundsNeverExceedWhatWasPaid() throws Exception {
        // 18.00 × 3，学生医保 80%：每件自付 3.60，合计 10.80
        Paid paid = paidCashOrder(STUDENT, "ZL-QCHY", 3);
        ChargeOrderItem item = items(paid.orderId()).getFirst();

        Map<String, Integer> outcomes = race(20, () -> refundService.apply(paid.orderId(),
                new RefundApplyDTO(List.of(new RefundApplyDTO.Line(item.getId(), 1)), "并发部分退费"),
                UUID.randomUUID().toString(), CASHIER_ID));
        confirmCash(paid.orderId());
        ChargeOrder mid = orderMapper.selectById(paid.orderId());
        assertThat(mid.getRefundedAmount().compareTo(mid.getPaidAmount())).isLessThanOrEqualTo(0);

        int remaining = 3 - itemMapper.selectById(item.getId()).getRefundedQty();
        if (remaining > 0) {
            refundService.apply(paid.orderId(), new RefundApplyDTO(
                            List.of(new RefundApplyDTO.Line(item.getId(), remaining)), "补齐"),
                    UUID.randomUUID().toString(), CASHIER_ID);
            confirmCash(paid.orderId());
        }
        ChargeOrder done = orderMapper.selectById(paid.orderId());
        Money sum = succeededRefunds(paid.orderId()).stream().map(RefundOrder::getAmount).reduce(Money.ZERO, Money::add);
        System.out.printf("[M5] partial: attempts=20 outcomes=%s refunds=%d sum=%s paid=%s%n", outcomes,
                succeededRefunds(paid.orderId()).size(), sum, done.getPaidAmount());
        assertThat(sum).isEqualTo(done.getPaidAmount());
        assertThat(done.getRefundedAmount()).isEqualTo(done.getPaidAmount());
        assertThat(done.getStatus()).isEqualTo(ChargeStatus.REFUNDED.code());
        assertThatThrownBy(() -> refundService.apply(paid.orderId(), new RefundApplyDTO(
                List.of(new RefundApplyDTO.Line(item.getId(), 1)), "再退一次"), UUID.randomUUID().toString(),
                CASHIER_ID)).isInstanceOf(BizException.class);
    }

    // ------------------------------------------------------------------ 流程

    @Test
    void refundingRegistrationFeeCancelsTheRegistrationAndReturnsTheSlot() throws Exception {
        ScheduleSlot slot = slots.create(SlotType.OUTPATIENT.code(), 2, "GH-PT");
        JsonNode reg = api.data(api.post(cashier, "/api/schedule/registrations",
                Map.of("patientId", STUDENT, "slotId", slot.getId())));
        String regNo = reg.at("/registration/regNo").asString();
        Long orderId = reg.at("/chargeOrder/id").asLong();
        // 窗口扫微信付款码收自付 0.20
        String payNo = checkout.checkout(orderId, new CheckoutDTO(List.of(
                Tender.authCode(Money.of("0.20"), WECHAT_CODE)), null, null), UUID.randomUUID().toString(), CASHIER_ID)
                .tenders().getFirst().payNo();
        assertThat(registration(regNo).getStatus()).isEqualTo(RegistrationStatus.BOOKED);
        assertThat(slots.reload(slot.getId()).getRemaining()).isEqualTo(1);

        Long itemId = items(orderId).getFirst().getId();
        JsonNode refund = api.data(applyRefund(orderId, itemId, 1)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.amount").value("0.20"))
                .andExpect(jsonPath("$.data.insuranceAmount").value("0.80"))
                .andExpect(jsonPath("$.data.status").value(RefundStatus.SUCCESS)));

        assertThat(gateway.refundedAmount(payNo)).isEqualTo(Money.of("0.20"));
        assertThat(registration(regNo).getStatus()).isEqualTo(RegistrationStatus.CANCELLED);
        assertThat(slots.reload(slot.getId()).getRemaining()).isEqualTo(2);
        List<FundLedger> red = ledger(refund.get("refundNo").asString());
        assertThat(red).hasSize(1);
        assertThat(red.getFirst().getAmount()).isEqualByComparingTo("-0.20");
    }

    @Test
    void largeRefundNeedsApprovalAndRejectionRestoresRefundableQuantity() throws Exception {
        // 教工自费：心电图 25.00 × 10 = 250.00，超过 200 元自动通过阈值
        Paid paid = paidCashOrder(STAFF, "JC-XD", 10);
        Long itemId = items(paid.orderId()).getFirst().getId();

        JsonNode pending = api.data(applyRefund(paid.orderId(), itemId, 10)
                .andExpect(jsonPath("$.data.status").value(RefundStatus.PENDING_APPROVAL)));
        String refundId = pending.get("id").asString();
        assertThat(itemMapper.selectById(itemId).getRefundedQty()).isEqualTo(10);
        // 待审核期间不能再申请
        applyRefund(paid.orderId(), itemId, 1).andExpect(status().isConflict());

        api.post(cashier, "/api/charge/refunds/" + refundId + "/reject", Map.of("note", "x"))
                .andExpect(status().isForbidden());
        // 驳回必须写明理由
        api.post(finance, "/api/charge/refunds/" + refundId + "/reject", null).andExpect(status().isBadRequest());
        api.post(finance, "/api/charge/refunds/" + refundId + "/reject", Map.of("note", "检查已做，不符合退费条件"))
                .andExpect(status().isOk());
        assertThat(itemMapper.selectById(itemId).getRefundedQty()).isZero();
        api.get(finance, "/api/charge/refunds?status=4")
                .andExpect(jsonPath("$.data.list[?(@.id == '" + refundId + "')].approverId").value(
                        String.valueOf(FINANCE_ID)))
                .andExpect(jsonPath("$.data.list[?(@.id == '" + refundId + "')].approveNote").value(
                        "检查已做，不符合退费条件"))
                .andExpect(jsonPath("$.data.list[?(@.id == '" + refundId + "')].applicantId").value(
                        String.valueOf(CASHIER_ID)));

        String second = api.data(applyRefund(paid.orderId(), itemId, 10)).get("id").asString();
        // 审批通过后现金不会自动记为已退：待收费员当面退还并确认
        JsonNode approved = api.data(api.post(finance, "/api/charge/refunds/" + second + "/approve",
                        Map.of("note", "检查未做，同意退费"))
                .andExpect(jsonPath("$.data.status").value(RefundStatus.REFUNDING))
                .andExpect(jsonPath("$.data.approverId").value(String.valueOf(FINANCE_ID)))
                .andExpect(jsonPath("$.data.approveNote").value("检查未做，同意退费"))
                .andExpect(jsonPath("$.data.approveTime").isNotEmpty())
                .andExpect(jsonPath("$.data.tenders[0].status").value(RefundTender.AWAITING_CONFIRM)));
        String tenderPath = "/api/charge/refunds/" + second + "/tenders/" + approved.get("tenders").get(0).get("id").asString()
                + "/confirm";
        // 只有收费员能确认，确认人取自登录身份
        api.post(doctor, tenderPath, Map.of()).andExpect(status().isForbidden());
        api.post(cashier, tenderPath, Map.of("note", "患者当面签收"))
                .andExpect(jsonPath("$.data.status").value(RefundStatus.SUCCESS))
                .andExpect(jsonPath("$.data.amount").value("250.00"))
                .andExpect(jsonPath("$.data.tenders[0].confirmBy").value(CASHIER_ID))
                .andExpect(jsonPath("$.data.tenders[0].confirmNote").value("患者当面签收"));
        assertThat(orderMapper.selectById(paid.orderId()).getStatus()).isEqualTo(ChargeStatus.REFUNDED.code());
    }

    @Test
    void applicantCannotApproveTheirOwnRefund() throws Exception {
        // 管理员同时有申请和审批权限：自己申请的退费不能自己审批（不相容岗位分离），由财务审批
        String admin = api.login("admin", "Admin@2026");
        Paid paid = paidCashOrder(STAFF, "JC-XD", 10);
        Long itemId = items(paid.orderId()).getFirst().getId();
        String refundId = api.data(mvc.perform(MockMvcRequestBuilders.post("/api/charge/orders/" + paid.orderId()
                        + "/refunds")
                .header("Authorization", admin)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("reason", "整单退",
                        "items", List.of(Map.of("chargeItemId", itemId.toString(), "qty", 10)))))))
                .get("id").asString();
        api.post(admin, "/api/charge/refunds/" + refundId + "/approve", null)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(ChargeErrorCode.SAME_PERSON.code()));
        api.post(admin, "/api/charge/refunds/" + refundId + "/reject", Map.of("note", "自己驳回"))
                .andExpect(status().isForbidden());
        api.post(finance, "/api/charge/refunds/" + refundId + "/approve", null)
                .andExpect(jsonPath("$.data.status").value(RefundStatus.REFUNDING));
    }

    @Test
    void failedChannelRefundIsRetriedUntilItSucceeds() throws Exception {
        ChargeOrderVO order = chargeOrderService.createCounterOrder(
                new ChargeCreateDTO(STUDENT, List.of(new ChargeItemDTO("JC-NCG", 1))));
        String payNo = checkout.checkout(order.id(), new CheckoutDTO(List.of(
                Tender.authCode(order.payableAmount(), WECHAT_CODE)), null, null), UUID.randomUUID().toString(),
                CASHIER_ID).tenders().getFirst().payNo();

        gateway.failNextRefunds(1);
        RefundVO refund = refundService.apply(order.id(), new RefundApplyDTO(
                        List.of(new RefundApplyDTO.Line(items(order.id()).getFirst().getId(), 1)), "渠道失败重试"),
                UUID.randomUUID().toString(), CASHIER_ID);
        assertThat(refund.status()).isEqualTo(RefundStatus.FAILED);
        assertThat(orderMapper.selectById(order.id()).getRefundedAmount()).isEqualTo(Money.ZERO);

        refundService.retry(100);

        assertThat(refundMapper.selectById(refund.id()).getStatus()).isEqualTo(RefundStatus.SUCCESS);
        assertThat(gateway.refundedAmount(payNo)).isEqualTo(Money.of("1.60"));
        assertThat(orderMapper.selectById(order.id()).getRefundedAmount()).isEqualTo(Money.of("1.60"));
    }

    @Test
    void unpaidOrderCannotBeRefunded() {
        ChargeOrderVO order = chargeOrderService.createCounterOrder(
                new ChargeCreateDTO(STUDENT, List.of(new ChargeItemDTO("JC-NCG", 1))));
        assertThatThrownBy(() -> refundService.apply(order.id(), new RefundApplyDTO(
                        List.of(new RefundApplyDTO.Line(items(order.id()).getFirst().getId(), 1)), "未缴费"),
                UUID.randomUUID().toString(), CASHIER_ID))
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ChargeErrorCode.ORDER_NOT_REFUNDABLE));
    }

    // ------------------------------------------------------------------ 辅助方法

    private record Paid(Long orderId, String payNo) {
    }

    /** 收费员确认该收费单上所有待确认的现金、银行卡退款 */
    private void confirmCash(Long orderId) {
        for (RefundVO r : refundService.listByOrder(orderId)) {
            for (RefundTender t : refundTenderMapper.selectByRefund(r.id())) {
                if (t.getStatus() == RefundTender.AWAITING_CONFIRM) {
                    refundService.confirmTender(r.id(), t.getId(), null, null, CASHIER_ID);
                }
            }
        }
    }

    private Paid paidCashOrder(long patientId, String itemCode, int qty) {
        ChargeOrderVO order = chargeOrderService.createCounterOrder(
                new ChargeCreateDTO(patientId, List.of(new ChargeItemDTO(itemCode, qty))));
        PayResultVO pay = checkout.cashPay(order.id(), order.payableAmount(), UUID.randomUUID().toString(),
                CASHIER_ID);
        return new Paid(order.id(), pay.payNo());
    }

    private ResultActions applyRefund(Long orderId, Long itemId, int qty)
            throws Exception {
        return mvc.perform(MockMvcRequestBuilders.post("/api/charge/orders/" + orderId + "/refunds")
                .header("Authorization", cashier)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("reason", "测试退费",
                        "items", List.of(Map.of("chargeItemId", itemId.toString(), "qty", qty))))));
    }

    private Map<String, Integer> race(int attempts, Runnable action) throws Exception {
        Map<String, Integer> outcomes = new ConcurrentHashMap<>();
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(attempts)) {
            for (int i = 0; i < attempts; i++) {
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
            for (Future<?> f : futures) {
                f.get(60, TimeUnit.SECONDS);
            }
        }
        return outcomes;
    }

    private List<ChargeOrderItem> items(Long orderId) {
        return itemMapper.selectList(Wrappers.<ChargeOrderItem>lambdaQuery()
                .eq(ChargeOrderItem::getChargeOrderId, orderId).orderByAsc(ChargeOrderItem::getId));
    }

    private List<RefundOrder> succeededRefunds(Long orderId) {
        return refundMapper.selectList(Wrappers.<RefundOrder>lambdaQuery()
                .eq(RefundOrder::getChargeOrderId, orderId).eq(RefundOrder::getStatus, RefundStatus.SUCCESS));
    }

    private Registration registration(String regNo) {
        return registrationMapper.selectOne(Wrappers.<Registration>lambdaQuery().eq(Registration::getRegNo, regNo));
    }

    private List<FundLedger> ledger(String bizNo) {
        return ledgerMapper.selectList(Wrappers.<FundLedger>lambdaQuery().eq(FundLedger::getBizNo, bizNo));
    }

    private BigDecimal ledgerSum(String bizNo) {
        return ledger(bizNo).stream().map(FundLedger::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
