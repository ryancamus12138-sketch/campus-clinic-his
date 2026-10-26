package io.github.ryancamus.clinic.charge;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import io.github.ryancamus.clinic.charge.domain.dto.ChargeCreateDTO;
import io.github.ryancamus.clinic.charge.domain.dto.ChargeItemDTO;
import io.github.ryancamus.clinic.charge.domain.dto.CheckoutDTO.Tender;
import io.github.ryancamus.clinic.charge.domain.dto.CheckoutDTO;
import io.github.ryancamus.clinic.charge.domain.entity.ChargeOrder;
import io.github.ryancamus.clinic.charge.domain.entity.PayOrder;
import io.github.ryancamus.clinic.charge.domain.vo.ChargeOrderVO;
import io.github.ryancamus.clinic.charge.enums.ChargeStatus;
import io.github.ryancamus.clinic.charge.enums.PayStatus;
import io.github.ryancamus.clinic.charge.mapper.ChargeOrderMapper;
import io.github.ryancamus.clinic.charge.mapper.PayOrderMapper;
import io.github.ryancamus.clinic.charge.service.ChargeOrderService;
import io.github.ryancamus.clinic.charge.service.CounterCheckoutService;
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
import io.github.ryancamus.clinic.schedule.service.BookingService;
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
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 窗口挂号与窗口收费：挂号生成挂号费收费单、现金收费与找零、未缴费超时退号，以及收费与作废同时发生时只有一方生效。
 */
@IntegrationTest
class CounterRegistrationIntegrationTest {

    private static final long STUDENT = 90001L;
    private static final long STAFF = 90002L;
    private static final long CASHIER = 2L;

    @Autowired
    private MockMvc mvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private StringRedisTemplate redis;
    @Autowired
    private SlotFixtures slots;
    @Autowired
    private ChargeOrderService chargeOrderService;
    @Autowired
    private CounterCheckoutService checkout;
    @Autowired
    private BookingService bookingService;
    @Autowired
    private ChargeOrderMapper chargeOrderMapper;
    @Autowired
    private PayOrderMapper payOrderMapper;
    @Autowired
    private RegistrationMapper registrationMapper;
    @Autowired
    private FundLedgerMapper ledgerMapper;

    private ApiClient api;
    private String cashier;
    private String doctor;

    @BeforeEach
    void setUp() throws Exception {
        api = new ApiClient(mvc, objectMapper, redis);
        cashier = api.login("cashier01", ApiClient.STAFF_PASSWORD);
        doctor = api.login("doctor01", ApiClient.STAFF_PASSWORD);
    }

    @Test
    void cashierRegistersAStudentAndCollectsTheRegistrationFee() throws Exception {
        ScheduleSlot slot = slots.create(SlotType.OUTPATIENT.code(), 5, "GH-PT");
        Map<String, Object> body = Map.of("patientId", STUDENT, "slotId", slot.getId());
        api.post(doctor, "/api/schedule/registrations", body).andExpect(status().isForbidden());

        JsonNode result = api.data(api.post(cashier, "/api/schedule/registrations", body)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.registration.status").value(RegistrationStatus.PENDING_PAYMENT))
                .andExpect(jsonPath("$.data.chargeOrder.totalAmount").value("1.00"))
                .andExpect(jsonPath("$.data.chargeOrder.insuranceAmount").value("0.80"))
                .andExpect(jsonPath("$.data.chargeOrder.payableAmount").value("0.20"))
                .andExpect(jsonPath("$.data.chargeOrder.feePolicyCode").value("STU")));
        String regNo = result.at("/registration/regNo").asString();
        String chargeId = result.at("/chargeOrder/id").asString();
        // 重复取挂号费收费单返回同一张
        api.post(cashier, "/api/schedule/registrations/" + regNo + "/charge", null)
                .andExpect(jsonPath("$.data.id").value(chargeId));
        // 同一患者不能重复挂同一时段
        api.post(cashier, "/api/schedule/registrations", body).andExpect(status().isConflict());

        // 学生医保：在测试替身中同步结算统筹，窗口收自付 0.20 元现金
        JsonNode paid = api.data(cash(chargeId, "1.00", UUID.randomUUID().toString())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.change").value("0.80")));
        assertThat(registration(regNo).getStatus()).isEqualTo(RegistrationStatus.BOOKED);
        assertThat(ledgerSum(paid.get("payNo").asString())).isEqualByComparingTo("0.20");
        api.get(cashier, "/api/schedule/registrations", "patientId", STUDENT).andExpect(status().isOk());
        assertThat(bookingService.listByPatient(STUDENT)).anyMatch(b -> b.regNo().equals(regNo)
                && b.status() == RegistrationStatus.BOOKED);
        // 已缴费的挂号不能直接退号，要走退费
        api.post(cashier, "/api/schedule/registrations/" + regNo + "/cancel", null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(20009));
    }

    @Test
    void cashierChargesStaffAtFullPriceAndGivesChangeOnce() throws Exception {
        Map<String, Object> body = Map.of("patientId", STAFF, "items", List.of(
                Map.of("itemCode", "JC-XCG", "qty", 1), Map.of("itemCode", "ZL-JRZS", "qty", 2)));
        api.post(cashier, "/api/charge/orders/preview", body)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.feePolicyCode").value("SELF"))
                .andExpect(jsonPath("$.data.payableAmount").value("28.00"));
        JsonNode order = api.data(api.post(cashier, "/api/charge/orders", body).andExpect(status().isOk()));
        String id = order.get("id").asString();

        String key = UUID.randomUUID().toString();
        JsonNode result = api.data(cash(id, "30.00", key)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value(PayStatus.SUCCESS))
                .andExpect(jsonPath("$.data.change").value("2.00")));
        // 网络重试：同一个幂等键不会重复收费
        cash(id, "30.00", key).andExpect(jsonPath("$.data.payNo").value(result.get("payNo").asString()));
        // 新的结算请求：已付清
        cash(id, "30.00", UUID.randomUUID().toString())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(30006));

        String payNo = result.get("payNo").asString();
        List<FundLedger> rows = ledger(payNo);
        assertThat(rows).extracting(FundLedger::getItemType).containsExactly(3, 4);
        assertThat(ledgerSum(payNo)).isEqualByComparingTo("28.00");
        api.get(cashier, "/api/charge/orders/" + id)
                .andExpect(jsonPath("$.data.status").value(ChargeStatus.PAID.code()))
                .andExpect(jsonPath("$.data.items.length()").value(2))
                .andExpect(jsonPath("$.data.pays.length()").value(1));
    }

    @Test
    void cashMustCoverThePayableAmount() throws Exception {
        String id = counterOrder(STAFF, "JC-NCG").getId().toString();
        cash(id, "7.99", UUID.randomUUID().toString())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(30008));
    }

    @Test
    void unpaidRegistrationIsReleasedAfterTheDeadline() throws Exception {
        ScheduleSlot slot = slots.create(SlotType.VACCINE.code(), 1, "YM-FW");
        JsonNode r = api.data(api.post(cashier, "/api/schedule/registrations",
                Map.of("patientId", STUDENT, "slotId", slot.getId())));
        String regNo = r.at("/registration/regNo").asString();
        makeOverdue(regNo);

        bookingService.expireOverdue(500);

        assertThat(registration(regNo).getStatus()).isEqualTo(RegistrationStatus.EXPIRED);
        assertThat(chargeOrderMapper.selectById(registration(regNo).getChargeOrderId()).getStatus())
                .isEqualTo(ChargeStatus.CLOSED.code());
        assertThat(slots.reload(slot.getId()).getRemaining()).isEqualTo(1);
    }

    /**
     * 同一张收费单：10 个窗口在收现金、10 个请求同时作废，重复 10 轮。
     * 收费单行锁保证两者只有一方生效：收了钱的单不会被作废，作废了的单不会收到钱。
     */
    @Test
    void checkoutAndCancelRacingOnTheSameOrderNeverBothSucceed() throws Exception {
        int paidAndClosed = 0;
        int paidTwice = 0;
        Map<String, Integer> outcomes = new ConcurrentHashMap<>();
        for (int round = 0; round < 10; round++) {
            ChargeOrderVO order = chargeOrderService.createCounterOrder(
                    new ChargeCreateDTO(STAFF, List.of(new ChargeItemDTO("JC-XD", 1))));
            CountDownLatch start = new CountDownLatch(1);
            try (var pool = Executors.newFixedThreadPool(20)) {
                List<Future<?>> futures = new ArrayList<>();
                for (int i = 0; i < 20; i++) {
                    boolean pay = i % 2 == 0;
                    futures.add(pool.submit(() -> {
                        start.await();
                        try {
                            if (pay) {
                                checkout.checkout(order.id(), new CheckoutDTO(List.of(
                                        Tender.cash(Money.of("25.00"), null)), null, null),
                                        UUID.randomUUID().toString(), CASHIER);
                                outcomes.merge("PAID", 1, Integer::sum);
                            } else {
                                chargeOrderService.cancel(order.id());
                                outcomes.merge("CANCELLED", 1, Integer::sum);
                            }
                        } catch (BizException e) {
                            outcomes.merge((pay ? "PAY_" : "CANCEL_") + e.getErrorCode().code(), 1, Integer::sum);
                        }
                        return null;
                    }));
                }
                start.countDown();
                for (var f : futures) {
                    f.get();
                }
            }
            ChargeOrder after = chargeOrderMapper.selectById(order.id());
            long pays = payOrderMapper.selectCount(Wrappers.<PayOrder>lambdaQuery()
                    .eq(PayOrder::getChargeOrderId, order.id()).eq(PayOrder::getStatus, PayStatus.SUCCESS));
            if (pays > 0 && after.getStatus() == ChargeStatus.CLOSED.code()) {
                paidAndClosed++;
            }
            if (pays > 1) {
                paidTwice++;
            }
        }
        System.out.printf("[M26] checkout vs cancel x10: outcomes=%s paidAndClosed=%d paidTwice=%d%n", outcomes,
                paidAndClosed, paidTwice);
        assertThat(paidAndClosed).isZero();
        assertThat(paidTwice).isZero();
    }

    // ------------------------------------------------------------------ 辅助方法

    private ChargeOrder counterOrder(long patientId, String itemCode) {
        ChargeOrderVO vo = chargeOrderService.createCounterOrder(
                new ChargeCreateDTO(patientId, List.of(new ChargeItemDTO(itemCode, 1))));
        return chargeOrderMapper.selectById(vo.id());
    }

    private void makeOverdue(String regNo) {
        Registration reg = registration(regNo);
        LocalDateTime past = LocalDateTime.now().minusDays(1);
        registrationMapper.update(null, Wrappers.<Registration>lambdaUpdate()
                .set(Registration::getExpireTime, past).eq(Registration::getId, reg.getId()));
        chargeOrderMapper.update(null, Wrappers.<ChargeOrder>lambdaUpdate()
                .set(ChargeOrder::getExpireTime, past).eq(ChargeOrder::getId, reg.getChargeOrderId()));
    }

    private ResultActions cash(String id, String received, String key) throws Exception {
        return mvc.perform(MockMvcRequestBuilders.post("/api/charge/orders/" + id + "/cash")
                .header("Authorization", cashier)
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"received\":\"" + received + "\"}"));
    }

    private Registration registration(String regNo) {
        return registrationMapper.selectOne(Wrappers.<Registration>lambdaQuery().eq(Registration::getRegNo, regNo));
    }

    private List<FundLedger> ledger(String payNo) {
        return ledgerMapper.selectList(Wrappers.<FundLedger>lambdaQuery()
                .eq(FundLedger::getBizNo, payNo).orderByAsc(FundLedger::getItemType));
    }

    private BigDecimal ledgerSum(String payNo) {
        return ledger(payNo).stream().map(FundLedger::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
