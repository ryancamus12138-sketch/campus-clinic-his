package io.github.ryancamus.clinic.api;

import io.github.ryancamus.clinic.schedule.SlotFixtures;
import io.github.ryancamus.clinic.schedule.domain.entity.ScheduleSlot;
import io.github.ryancamus.clinic.schedule.enums.SlotType;
import io.github.ryancamus.clinic.support.ApiClient;
import io.github.ryancamus.clinic.support.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 管理端接口的端到端用例：以真实角色通过 HTTP 走一遍排班、开方、药房、收费、财务的主要页面接口，
 * 校验返回结构与关键业务规则（权限、参数校验、状态流转）。
 */
@IntegrationTest
class ManagementApiIntegrationTest {

    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");

    @Autowired
    private MockMvc mvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private StringRedisTemplate redis;
    @Autowired
    private SlotFixtures slots;

    private ApiClient api;
    private String admin;
    private String cashier;
    private String doctor;
    private String pharmacist;
    private String finance;

    @BeforeEach
    void setUp() throws Exception {
        api = new ApiClient(mvc, objectMapper, redis);
        admin = api.login("admin", "Admin@2026");
        cashier = api.login("cashier01", ApiClient.STAFF_PASSWORD);
        doctor = api.login("doctor01", ApiClient.STAFF_PASSWORD);
        pharmacist = api.login("pharm01", ApiClient.STAFF_PASSWORD);
        finance = api.login("finance01", ApiClient.STAFF_PASSWORD);
    }

    @Test
    void schedulingPagesValidateInputAndToggleSlots() throws Exception {
        LocalDate date = LocalDate.now(SHANGHAI).plusDays(200);
        Map<String, Object> batch = Map.of("campusId", 1, "deptId", 101, "slotType", 1, "dates", List.of(date.toString()),
                "periods", List.of(Map.of("startTime", "08:00", "endTime", "08:30", "total", 5),
                        Map.of("startTime", "08:30", "endTime", "09:00", "total", 5)),
                "priceItemCode", "GH-PT");
        api.post(admin, "/api/schedule/slots/batch", batch).andExpect(jsonPath("$.data").value(2));
        // 重复提交：已存在的时段跳过
        api.post(admin, "/api/schedule/slots/batch", batch).andExpect(jsonPath("$.data").value(0));
        // 结束早于开始
        api.post(admin, "/api/schedule/slots/batch", Map.of("campusId", 1, "deptId", 101, "slotType", 1,
                        "dates", List.of(date.toString()), "periods", List.of(Map.of("startTime", "10:00",
                                "endTime", "09:00", "total", 5)), "priceItemCode", "GH-PT"))
                .andExpect(status().isBadRequest());
        // 价目不存在
        api.post(admin, "/api/schedule/slots/batch", Map.of("campusId", 1, "deptId", 101, "slotType", 1,
                        "dates", List.of(date.toString()), "periods", List.of(Map.of("startTime", "11:00",
                                "endTime", "11:30", "total", 5)), "priceItemCode", "NOPE"))
                .andExpect(status().isBadRequest());

        JsonNode page = api.data(api.get(admin, "/api/schedule/slots", "campusId", 1, "deptId", 101,
                "dateFrom", date, "dateTo", date));
        assertThat(page.get("total").asInt()).isEqualTo(2);
        String id = page.at("/list/0/id").asString();
        api.put(admin, "/api/schedule/slots/" + id + "/status", Map.of("status", 2)).andExpect(status().isOk());
        api.get(cashier, "/api/schedule/registrations/slots", "slotType", 1, "date", date, "campusId", 1)
                .andExpect(jsonPath("$.data.length()").value(1));

        api.get(admin, "/api/system/depts", "campusId", 1).andExpect(jsonPath("$.data.length()").isNumber());
        // 校区、收费点来自数据库，不写死在页面
        api.get(cashier, "/api/system/campuses").andExpect(jsonPath("$.data[0].name").value("主校区"));
        api.put(cashier, "/api/system/campuses/2", Map.of("name", "东校区")).andExpect(status().isForbidden());
        api.put(admin, "/api/system/campuses/2", Map.of("name", "主校区")).andExpect(status().isConflict());
        api.put(admin, "/api/system/campuses/2", Map.of("name", "东校区", "address", "示例路 1 号"))
                .andExpect(jsonPath("$.data.address").value("示例路 1 号"));
        JsonNode points = api.data(api.get(cashier, "/api/system/charge-points"));
        String pointId = points.get(0).get("id").asString();
        Map<String, Object> pointBody = Map.of("name", "主校区收费处", "counterMethods", List.of("CASH", "BANK_CARD"),
                "status", 1);
        api.put(cashier, "/api/system/charge-points/" + pointId, pointBody).andExpect(status().isForbidden());
        api.put(admin, "/api/system/charge-points/" + pointId, pointBody)
                .andExpect(jsonPath("$.data.counterMethods.length()").value(2));
        // 主校区只开放现金、银行卡：校园卡、付款码即使平台已接入也不显示
        api.get(cashier, "/api/charge/orders/tender-methods", "campusId", 1)
                .andExpect(jsonPath("$.data.length()").value(2));
        api.put(admin, "/api/system/charge-points/" + pointId, Map.of("name", "主校区收费处",
                "counterMethods", List.of("CASH", "BANK_CARD", "CAMPUS_CARD", "AUTH_CODE"), "status", 1))
                .andExpect(status().isOk());
        api.get(admin, "/api/system/doctors", "deptId", 101).andExpect(status().isOk());
        api.get(admin, "/api/system/users", "keyword", "cashier").andExpect(jsonPath("$.data.total").isNumber());
        // 其他测试会新增检查项目，这里只确认演示数据中的项目在列表里
        api.get(cashier, "/api/charge/price-items", "itemType", 4)
                .andExpect(jsonPath("$.data[?(@.itemCode == 'JC-XCG')]").exists());
    }

    @Test
    void doctorSeesTheDayVisitListAndPrescriptionHistory() throws Exception {
        ScheduleSlot slot = slots.createTomorrow(SlotType.OUTPATIENT.code(), 3, "GH-MF");
        String regNo = api.data(api.post(cashier, "/api/schedule/registrations",
                Map.of("patientId", 90001, "slotId", slot.getId()))).at("/registration/regNo").asString();

        JsonNode visits = api.data(api.get(doctor, "/api/clinic/visits", "date", slot.getSlotDate()));
        JsonNode visit = null;
        for (JsonNode v : visits) {
            if (v.get("regNo").asString().equals(regNo)) {
                visit = v;
            }
        }
        assertThat(visit).isNotNull();
        assertThat(visit.get("patientName").asString()).isEqualTo("演示学生");
        String registrationId = visit.get("registrationId").asString();

        api.post(doctor, "/api/clinic/prescriptions", Map.of("registrationId", registrationId,
                        "items", List.of(Map.of("kind", 1, "qty", 1))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(50003));
        api.post(doctor, "/api/clinic/prescriptions", Map.of("registrationId", registrationId,
                        "items", List.of(Map.of("kind", 2, "itemCode", "JC-XCG", "qty", 1))))
                .andExpect(jsonPath("$.data.payableAmount").value("4.00"));
        api.get(doctor, "/api/clinic/prescriptions", "registrationId", registrationId)
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].chargeStatus").value(0));
        api.post(doctor, "/api/clinic/prescriptions", Map.of("registrationId", "1", "items",
                        List.of(Map.of("kind", 2, "itemCode", "JC-XCG", "qty", 1))))
                .andExpect(status().isNotFound());
        api.get(doctor, "/api/pharmacy/drugs", "keyword", "布洛芬")
                .andExpect(jsonPath("$.data[0].drugCode").value("YP-BLF"));
    }

    @Test
    void pharmacyStockPageAndInbound() throws Exception {
        JsonNode before = api.data(api.get(pharmacist, "/api/pharmacy/stock", "campusId", 2, "keyword", "YP-AMXL"));
        int onHand = before.at("/0/onHand").asInt();
        String batchNo = "T" + UUID.randomUUID().toString().substring(0, 8);
        api.post(pharmacist, "/api/pharmacy/inbound", Map.of("campusId", 2, "drugId", 4002, "batchNo", batchNo,
                "expireDate", LocalDate.now(SHANGHAI).plusYears(2).toString(), "purchasePrice", "6.00", "qty", 50))
                .andExpect(status().isOk());
        // 同批号再入库：累加
        api.post(pharmacist, "/api/pharmacy/inbound", Map.of("campusId", 2, "drugId", 4002, "batchNo", batchNo,
                "expireDate", LocalDate.now(SHANGHAI).plusYears(2).toString(), "purchasePrice", "6.00", "qty", 10))
                .andExpect(status().isOk());
        // 已过期的批次不能入库
        api.post(pharmacist, "/api/pharmacy/inbound", Map.of("campusId", 2, "drugId", 4002, "batchNo", "OLD1",
                "expireDate", LocalDate.now(SHANGHAI).minusDays(1).toString(), "purchasePrice", "6.00", "qty", 10))
                .andExpect(status().isBadRequest());
        JsonNode after = api.data(api.get(pharmacist, "/api/pharmacy/stock", "campusId", 2, "keyword", "YP-AMXL"));
        assertThat(after.at("/0/onHand").asInt()).isEqualTo(onHand + 60);
        // 医生没有入库权限
        api.post(doctor, "/api/pharmacy/inbound", Map.of("campusId", 2, "drugId", 4002, "batchNo", batchNo,
                "expireDate", LocalDate.now(SHANGHAI).plusYears(2).toString(), "purchasePrice", "6.00", "qty", 1))
                .andExpect(status().isForbidden());
    }

    @Test
    void financePagesShiftReportReconAndInvoices() throws Exception {
        // 收费员现金收一笔，交班
        String orderId = api.data(api.post(cashier, "/api/charge/orders", Map.of("patientId", "90002",
                "items", List.of(Map.of("itemCode", "JC-NCG", "qty", 1))))).get("id").asString();
        // 缺少防重复提交的请求头：参数错误（400），而不是“系统繁忙”
        mvc.perform(MockMvcRequestBuilders.post("/api/charge/orders/" + orderId + "/cash")
                        .header("Authorization", cashier)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"received\":\"10.00\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(MockMvcRequestBuilders.post("/api/charge/orders/" + orderId + "/cash")
                .header("Authorization", cashier).header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON).content("{\"received\":\"10.00\"}"))
                .andExpect(jsonPath("$.data.change").value("2.00"));
        JsonNode current = api.data(api.get(cashier, "/api/finance/shifts/current"));
        String shiftId = current.get("id").asString();
        // 时间按“年-月-日 时:分:秒”输出，页面直接显示
        assertThat(current.get("startTime").asString()).matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}");
        api.post(pharmacist, "/api/finance/shifts/" + shiftId + "/hand-over", Map.of("countedCash", "0"))
                .andExpect(status().isForbidden());
        api.post(finance, "/api/finance/shifts/" + shiftId + "/review", null).andExpect(status().isConflict());
        JsonNode closed = api.data(api.post(cashier, "/api/finance/shifts/" + shiftId + "/hand-over",
                Map.of("countedCash", current.get("expectedCash").asString(),
                        "posCounted", current.get("posExpected").asString())));
        assertThat(closed.get("status").asInt()).isEqualTo(1);
        assertThat(closed.get("cashDiff").decimalValue()).isZero();
        api.post(cashier, "/api/finance/shifts/" + shiftId + "/hand-over", Map.of("countedCash", "0"))
                .andExpect(status().isConflict());
        api.post(finance, "/api/finance/shifts/" + shiftId + "/review", null)
                .andExpect(jsonPath("$.data.status").value(2));
        api.get(finance, "/api/finance/shifts").andExpect(jsonPath("$.data.total").isNumber());
        // 收费员只看到自己的班次
        JsonNode mine = api.data(api.get(cashier, "/api/finance/shifts", "size", 200));
        mine.get("list").forEach(s -> assertThat(s.get("cashierId").asString()).isEqualTo("2"));

        LocalDate today = LocalDate.now(SHANGHAI);
        api.post(finance, "/api/finance/reports/rebuild?date=" + today.minusDays(1), null).andExpect(status().isOk());
        JsonNode report = api.data(api.get(finance, "/api/finance/reports/summary", "from", today.minusDays(6),
                "to", today));
        JsonNode ledger = api.data(api.get(finance, "/api/finance/reports/summary", "from", today.minusDays(6),
                "to", today, "source", "ledger"));
        assertThat(report.get("total")).isEqualTo(ledger.get("total"));
        api.get(finance, "/api/finance/reports/summary", "from", today, "to", today.minusDays(1))
                .andExpect(status().isBadRequest());
        api.get(cashier, "/api/finance/reports/summary", "from", today, "to", today).andExpect(status().isForbidden());

        api.post(finance, "/api/finance/recon/run?date=" + today, null).andExpect(jsonPath("$.data.date").exists());
        JsonNode diffs = api.data(api.get(finance, "/api/finance/recon/diffs", "status", 0));
        if (diffs.get("total").asInt() > 0) {
            String diffId = diffs.at("/list/0/id").asString();
            api.post(finance, "/api/finance/recon/diffs/" + diffId + "/resolve?remark=checked", null)
                    .andExpect(status().isOk());
        }
        api.post(finance, "/api/finance/recon/diffs/1/resolve?remark=x", null).andExpect(status().isNotFound());

        api.get(finance, "/api/finance/invoices").andExpect(jsonPath("$.data.total").isNumber());
        api.get(finance, "/api/finance/invoices/connected").andExpect(status().isOk());
        api.get(finance, "/api/charge/refunds", "status", 2).andExpect(jsonPath("$.data.list").isArray());
    }
}
