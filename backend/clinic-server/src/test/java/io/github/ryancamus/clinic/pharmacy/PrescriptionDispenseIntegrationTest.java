package io.github.ryancamus.clinic.pharmacy;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import io.github.ryancamus.clinic.charge.domain.entity.ChargeOrderItem;
import io.github.ryancamus.clinic.charge.mapper.ChargeOrderItemMapper;
import io.github.ryancamus.clinic.charge.service.CounterCheckoutService;
import io.github.ryancamus.clinic.clinic.domain.entity.Prescription;
import io.github.ryancamus.clinic.clinic.mapper.PrescriptionMapper;
import io.github.ryancamus.clinic.common.money.Money;
import io.github.ryancamus.clinic.pharmacy.domain.entity.DrugBatch;
import io.github.ryancamus.clinic.pharmacy.domain.entity.DrugTraceCode;
import io.github.ryancamus.clinic.pharmacy.mapper.DrugBatchMapper;
import io.github.ryancamus.clinic.pharmacy.mapper.DrugTraceCodeMapper;
import io.github.ryancamus.clinic.pharmacy.service.TraceUploadService;
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

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 门诊主链路：挂号 → 医生开方（锁库存）→ 缴费 → 药房扫码发药 → 退药 → 退费。
 */
@IntegrationTest
class PrescriptionDispenseIntegrationTest {

    private static final long IBUPROFEN = 4001L;
    private static final long EARLY_BATCH = 5001L;
    private static final long AMBROXOL = 4004L;
    private static final AtomicLong CODE_SEQ = new AtomicLong(System.nanoTime() % 1_000_000_000L);

    @Autowired
    private MockMvc mvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private StringRedisTemplate redis;
    @Autowired
    private SlotFixtures slots;
    @Autowired
    private CounterCheckoutService checkout;
    @Autowired
    private DrugBatchMapper batchMapper;
    @Autowired
    private DrugTraceCodeMapper traceMapper;
    @Autowired
    private ChargeOrderItemMapper itemMapper;
    @Autowired
    private RegistrationMapper registrationMapper;
    @Autowired
    private PrescriptionMapper prescriptionMapper;
    @Autowired
    private TraceUploadService traceUploadService;

    private ApiClient api;
    private String doctor;
    private String cashier;
    private String pharmacist;

    @BeforeEach
    void setUp() throws Exception {
        api = new ApiClient(mvc, objectMapper, redis);
        doctor = api.login("doctor01", ApiClient.STAFF_PASSWORD);
        cashier = api.login("cashier01", ApiClient.STAFF_PASSWORD);
        pharmacist = api.login("pharm01", ApiClient.STAFF_PASSWORD);
    }

    @Test
    void fullOutpatientFlowFromPrescriptionToDispenseReturnAndRefund() throws Exception {
        Long registrationId = bookFreeVisit();
        DrugBatch before = batchMapper.selectById(EARLY_BATCH);

        // 1. 开方：布洛芬 2 盒（可报销）+ 肌肉注射 1 次，学生医保 80%
        JsonNode rx = api.data(prescribe(registrationId, List.of(
                        Map.of("kind", 1, "drugId", IBUPROFEN, "qty", 2, "usageNote", "每日 2 次，每次 1 粒"),
                        Map.of("kind", 2, "itemCode", "ZL-JRZS", "qty", 1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalAmount").value("29.00"))
                .andExpect(jsonPath("$.data.payableAmount").value("5.80")));
        String chargeNo = rx.get("chargeNo").asString();
        assertThat(registrationMapper.selectById(registrationId).getStatus()).isEqualTo(RegistrationStatus.VISITED);
        // 开单即锁定：先到期的批次锁定量 +2，在库不变
        DrugBatch locked = batchMapper.selectById(EARLY_BATCH);
        assertThat(locked.getQtyLocked() - before.getQtyLocked()).isEqualTo(2);
        assertThat(locked.getQtyOnHand()).isEqualTo(before.getQtyOnHand());

        // 未缴费不能发药
        api.get(pharmacist, "/api/pharmacy/dispense/" + chargeNo).andExpect(status().isNotFound());

        // 2. 收费处现金缴费
        Long chargeId = prescriptionMapper.selectById(rx.get("id").asLong()).getChargeOrderId();
        checkout.cashPay(chargeId, Money.of("10.00"), UUID.randomUUID().toString(), 2L);

        // 3. 药房扫码发药
        JsonNode view = api.data(api.get(pharmacist, "/api/pharmacy/dispense/" + chargeNo)
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].dispensable").value(2))
                .andExpect(jsonPath("$.data.items[0].traceRequired").value(true)));
        String itemId = view.at("/items/0/chargeItemId").asString();
        dispense(chargeNo, itemId, 2, List.of()).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40006));
        List<String> codes = List.of(traceCode(), traceCode());
        dispense(chargeNo, itemId, 2, codes).andExpect(status().isOk());
        // 重复点击发药
        dispense(chargeNo, itemId, 2, List.of(traceCode(), traceCode())).andExpect(status().isConflict());

        DrugBatch afterDispense = batchMapper.selectById(EARLY_BATCH);
        assertThat(afterDispense.getQtyOnHand()).isEqualTo(before.getQtyOnHand() - 2);
        assertThat(afterDispense.getQtyLocked()).isEqualTo(before.getQtyLocked());
        assertThat(traces(Long.valueOf(itemId))).hasSize(2)
                .allMatch(t -> t.getStatus() == DrugTraceCode.COLLECTED && t.getBatchId() == EARLY_BATCH);
        traceUploadService.uploadPending(500);
        assertThat(traces(Long.valueOf(itemId))).allMatch(t -> t.getStatus() == DrugTraceCode.UPLOADED);

        // 4. 已发药的不能直接退费
        refund(chargeId, itemId, 1).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value(30016));

        // 5. 退药 1 盒（扫码定位批次），再退费 1 盒：自付 5.00 的一半
        returnDrug(chargeNo, itemId, 1, List.of(codes.getFirst())).andExpect(status().isOk());
        assertThat(batchMapper.selectById(EARLY_BATCH).getQtyOnHand()).isEqualTo(before.getQtyOnHand() - 1);
        var refunded = api.data(refund(chargeId, itemId, 1).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.amount").value("2.50"))
                .andExpect(jsonPath("$.data.insuranceAmount").value("10.00")));
        // 现金退款由收费员当面退还后确认，确认后退费完成、释放药品锁定
        api.post(cashier, "/api/charge/refunds/" + refunded.get("id").asString() + "/tenders/"
                + refunded.get("tenders").get(0).get("id").asString() + "/confirm", Map.of())
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value(2));

        ChargeOrderItem item = itemMapper.selectById(Long.valueOf(itemId));
        assertThat(item.getDispensedQty()).isEqualTo(1);
        assertThat(item.getRefundedQty()).isEqualTo(1);
        DrugBatch end = batchMapper.selectById(EARLY_BATCH);
        assertThat(end.getQtyLocked()).isEqualTo(before.getQtyLocked());
    }

    @Test
    void sameTraceCodeCannotBeSoldTwiceUntilReturned() throws Exception {
        String code = traceCode();
        Dispensable first = paidPrescription(IBUPROFEN, 1);
        dispense(first.chargeNo(), first.itemId(), 1, List.of(code)).andExpect(status().isOk());

        Dispensable second = paidPrescription(IBUPROFEN, 1);
        dispense(second.chargeNo(), second.itemId(), 1, List.of(code))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(40008));

        // 第一张单退药后，这盒药可以再次发出
        returnDrug(first.chargeNo(), first.itemId(), 1, List.of(code)).andExpect(status().isOk());
        dispense(second.chargeNo(), second.itemId(), 1, List.of(code)).andExpect(status().isOk());
    }

    @Test
    void insufficientStockRejectsTheWholePrescription() throws Exception {
        Long registrationId = bookFreeVisit();
        int available = batchMapper.selectList(Wrappers.<DrugBatch>lambdaQuery()
                        .eq(DrugBatch::getDrugId, AMBROXOL).eq(DrugBatch::getCampusId, 1L))
                .stream().mapToInt(DrugBatch::available).sum();
        long prescriptionsBefore = prescriptionMapper.selectCount(Wrappers.<Prescription>lambdaQuery()
                .eq(Prescription::getRegistrationId, registrationId));

        // 每行最多开 99，用两行同一药品凑出超过可用量的需求
        int first = Math.min(99, available);
        int second = Math.min(99, available - first + 1);
        prescribe(registrationId, List.of(
                        Map.of("kind", 1, "drugId", AMBROXOL, "qty", first),
                        Map.of("kind", 1, "drugId", AMBROXOL, "qty", second)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(40002));

        assertThat(prescriptionMapper.selectCount(Wrappers.<Prescription>lambdaQuery()
                .eq(Prescription::getRegistrationId, registrationId))).isEqualTo(prescriptionsBefore);
        assertThat(batchMapper.selectList(Wrappers.<DrugBatch>lambdaQuery()
                        .eq(DrugBatch::getDrugId, AMBROXOL).eq(DrugBatch::getCampusId, 1L))
                .stream().mapToInt(DrugBatch::available).sum()).isEqualTo(available);
    }

    @Test
    void cancellingAnUnpaidPrescriptionReleasesLockedStock() throws Exception {
        Long registrationId = bookFreeVisit();
        int lockedBefore = batchMapper.selectById(EARLY_BATCH).getQtyLocked();
        JsonNode rx = api.data(prescribe(registrationId, List.of(Map.of("kind", 1, "drugId", IBUPROFEN, "qty", 3))));
        assertThat(batchMapper.selectById(EARLY_BATCH).getQtyLocked()).isEqualTo(lockedBefore + 3);

        Long chargeId = prescriptionMapper.selectById(rx.get("id").asLong()).getChargeOrderId();
        api.post(cashier, "/api/charge/orders/" + chargeId + "/cancel", null).andExpect(status().isOk());
        assertThat(batchMapper.selectById(EARLY_BATCH).getQtyLocked()).isEqualTo(lockedBefore);
    }

    @Test
    void registrationFeeIsNotRefundableOnceTheDoctorHasSeenThePatient() throws Exception {
        ScheduleSlot slot = slots.create(SlotType.OUTPATIENT.code(), 5, "GH-PT");
        JsonNode registered = api.data(api.post(cashier, "/api/schedule/registrations",
                Map.of("patientId", 90001, "slotId", slot.getId())).andExpect(status().isOk()));
        String regNo = registered.at("/registration/regNo").asString();
        Long chargeId = registered.at("/chargeOrder/id").asLong();
        checkout.cashPay(chargeId, Money.of("10.00"), UUID.randomUUID().toString(), 2L);
        Registration reg = registrationMapper.selectOne(Wrappers.<Registration>lambdaQuery()
                .eq(Registration::getRegNo, regNo));
        String regItemId = itemMapper.selectList(Wrappers.<ChargeOrderItem>lambdaQuery()
                .eq(ChargeOrderItem::getChargeOrderId, chargeId)).getFirst().getId().toString();

        prescribe(reg.getId(), List.of(Map.of("kind", 1, "drugId", IBUPROFEN, "qty", 1))).andExpect(status().isOk());

        refund(chargeId, regItemId, 1).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(30030));
        assertThat(itemMapper.selectById(Long.valueOf(regItemId)).getRefundedQty()).isZero();
    }

    // ------------------------------------------------------------------ 辅助方法

    private record Dispensable(String chargeNo, String itemId) {
    }

    private Dispensable paidPrescription(long drugId, int qty) throws Exception {
        Long registrationId = bookFreeVisit();
        JsonNode rx = api.data(prescribe(registrationId, List.of(Map.of("kind", 1, "drugId", drugId, "qty", qty))));
        Long chargeId = prescriptionMapper.selectById(rx.get("id").asLong()).getChargeOrderId();
        checkout.cashPay(chargeId, Money.of("100.00"), UUID.randomUUID().toString(), 2L);
        String chargeNo = rx.get("chargeNo").asString();
        String itemId = api.data(api.get(pharmacist, "/api/pharmacy/dispense/" + chargeNo))
                .at("/items/0/chargeItemId").asString();
        return new Dispensable(chargeNo, itemId);
    }

    /** 免费号：窗口挂号后直接是“已预约”，可以接诊 */
    private Long bookFreeVisit() throws Exception {
        ScheduleSlot slot = slots.create(SlotType.OUTPATIENT.code(), 5, "GH-MF");
        String regNo = api.data(api.post(cashier, "/api/schedule/registrations",
                Map.of("patientId", 90001, "slotId", slot.getId())).andExpect(status().isOk()))
                .at("/registration/regNo").asString();
        return registrationMapper.selectOne(Wrappers.<Registration>lambdaQuery().eq(Registration::getRegNo, regNo))
                .getId();
    }

    private ResultActions prescribe(Long registrationId, List<Map<String, Object>> items) throws Exception {
        return api.post(doctor, "/api/clinic/prescriptions",
                Map.of("registrationId", registrationId.toString(), "items", items));
    }

    private ResultActions dispense(String chargeNo, String itemId, int qty, List<String> codes) throws Exception {
        return api.post(pharmacist, "/api/pharmacy/dispense", Map.of("chargeNo", chargeNo,
                "items", List.of(Map.of("chargeItemId", itemId, "qty", qty, "traceCodes", codes))));
    }

    private ResultActions returnDrug(String chargeNo, String itemId, int qty, List<String> codes) throws Exception {
        return api.post(pharmacist, "/api/pharmacy/dispense/return", Map.of("chargeNo", chargeNo,
                "chargeItemId", itemId, "qty", qty, "traceCodes", codes));
    }

    private ResultActions refund(Long chargeId, String itemId, int qty) throws Exception {
        return mvc.perform(MockMvcRequestBuilders.post("/api/charge/orders/" + chargeId + "/refunds")
                .header("Authorization", cashier)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("reason", "退药后退费",
                        "items", List.of(Map.of("chargeItemId", itemId, "qty", qty))))));
    }

    private List<DrugTraceCode> traces(Long chargeItemId) {
        return traceMapper.selectList(Wrappers.<DrugTraceCode>lambdaQuery()
                .eq(DrugTraceCode::getChargeItemId, chargeItemId));
    }

    /** 测试用 20 位追溯码，进程内唯一 */
    private static String traceCode() {
        return "8" + String.format("%019d", CODE_SEQ.incrementAndGet());
    }
}
