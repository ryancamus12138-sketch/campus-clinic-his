package io.github.ryancamus.clinic.schedule;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@IntegrationTest
class BookingIntegrationTest {

    private static final long STUDENT_PATIENT_ID = 90001L;
    private static final long STAFF_PATIENT_ID = 90002L;

    @Autowired
    private MockMvc mvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private StringRedisTemplate redis;
    @Autowired
    private SlotFixtures slots;
    @Autowired
    private RegistrationMapper registrationMapper;
    @Autowired
    private BookingService bookingService;

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
    void paidSlotBookingIsPendingUntilPaymentAndCannotBeDuplicated() throws Exception {
        ScheduleSlot slot = slots.create(SlotType.OUTPATIENT.code(), 5, "GH-PT");

        JsonNode booking = api.data(register(STUDENT_PATIENT_ID, slot.getId())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.registration.status").value(RegistrationStatus.PENDING_PAYMENT))
                .andExpect(jsonPath("$.data.registration.price").value("1.00"))
                .andExpect(jsonPath("$.data.registration.expireTime").isNotEmpty()));
        assertThat(booking.at("/registration/regNo").asString()).startsWith("R");

        register(STUDENT_PATIENT_ID, slot.getId())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(20004));

        assertThat(slots.reload(slot.getId()).getRemaining()).isEqualTo(4);
        assertThat(redisStock(slot.getId())).isEqualTo("4");
    }

    @Test
    void lastSlotGoesToOnlyOnePatient() throws Exception {
        ScheduleSlot slot = slots.create(SlotType.VACCINE.code(), 1, "YM-FW");

        register(STUDENT_PATIENT_ID, slot.getId()).andExpect(status().isOk());
        register(STAFF_PATIENT_ID, slot.getId())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(20003));
        assertThat(slots.reload(slot.getId()).getRemaining()).isZero();
    }

    @Test
    void cancellingReturnsTheSlotAndAllowsRebooking() throws Exception {
        ScheduleSlot slot = slots.create(SlotType.OUTPATIENT.code(), 1, "GH-PT");
        String regNo = api.data(register(STUDENT_PATIENT_ID, slot.getId()))
                .at("/registration/regNo").asString();

        api.post(cashier, "/api/schedule/registrations/" + regNo + "/cancel", null).andExpect(status().isOk());

        assertThat(slots.reload(slot.getId()).getRemaining()).isEqualTo(1);
        assertThat(redisStock(slot.getId())).isEqualTo("1");
        register(STUDENT_PATIENT_ID, slot.getId()).andExpect(status().isOk());
    }

    @Test
    void overduePendingBookingIsReleasedByTheExpiryJob() throws Exception {
        ScheduleSlot slot = slots.create(SlotType.PHYSICAL_EXAM.code(), 1, "TJ-XS");
        String regNo = api.data(register(STUDENT_PATIENT_ID, slot.getId()))
                .at("/registration/regNo").asString();
        // 模拟 15 分钟已过
        registrationMapper.update(Wrappers.<Registration>lambdaUpdate()
                .set(Registration::getExpireTime, LocalDateTime.now().minusMinutes(1))
                .eq(Registration::getRegNo, regNo));

        assertThat(bookingService.expireOverdue(500)).isGreaterThanOrEqualTo(1);

        Registration reg = registrationMapper.selectOne(Wrappers.<Registration>lambdaQuery()
                .eq(Registration::getRegNo, regNo));
        assertThat(reg.getStatus()).isEqualTo(RegistrationStatus.EXPIRED);
        assertThat(slots.reload(slot.getId()).getRemaining()).isEqualTo(1);
        assertThat(redisStock(slot.getId())).isEqualTo("1");
        // 再次运行不会重复归还
        bookingService.expireOverdue(500);
        assertThat(slots.reload(slot.getId()).getRemaining()).isEqualTo(1);
        // 释放后本人可以重新预约
        register(STUDENT_PATIENT_ID, slot.getId()).andExpect(status().isOk());
    }

    @Test
    void freeSlotIsBookedImmediately() throws Exception {
        ScheduleSlot slot = slots.create(SlotType.OUTPATIENT.code(), 3, "GH-MF");
        register(STUDENT_PATIENT_ID, slot.getId())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.registration.status").value(RegistrationStatus.BOOKED))
                .andExpect(jsonPath("$.data.registration.expireTime").doesNotExist());
    }

    @Test
    void slotIsNotBookableBeforeItOpens() throws Exception {
        ScheduleSlot slot = slots.create(SlotType.VACCINE.code(), 10, "YM-FW", LocalDateTime.now().plusDays(1));
        register(STUDENT_PATIENT_ID, slot.getId())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(20002));
    }

    @Test
    void availableSlotsShowLivePriceAndRemaining() throws Exception {
        ScheduleSlot slot = slots.create(SlotType.VACCINE.code(), 7, "YM-FW");
        api.get(cashier, "/api/schedule/registrations/slots", "slotType", SlotType.VACCINE.code(), "date", slot.getSlotDate())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value(String.valueOf(slot.getId())))
                .andExpect(jsonPath("$.data[0].price").value("25.00"))
                .andExpect(jsonPath("$.data[0].remaining").value(7))
                .andExpect(jsonPath("$.data[0].deptName").value("内科"));
    }

    @Test
    void onlyCashiersCanRegisterAndUnknownRegistrationIsNotFound() throws Exception {
        ScheduleSlot slot = slots.create(SlotType.OUTPATIENT.code(), 5, "GH-PT");
        api.post(doctor, "/api/schedule/registrations", Map.of("patientId", STUDENT_PATIENT_ID, "slotId", slot.getId()))
                .andExpect(status().isForbidden());
        api.post(cashier, "/api/schedule/registrations/R-NOT-EXIST/cancel", null)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(20007));
    }

    @Test
    void staffCanBatchCreateSlotsAndRepeatedBatchIsIdempotent() throws Exception {
        String admin = api.login("admin", "Admin@2026");
        LocalDate date = LocalDate.now().plusDays(200);
        Map<String, Object> batch = Map.of(
                "campusId", 1, "deptId", 102, "slotType", 1,
                "dates", new String[]{date.toString(), date.plusDays(1).toString()},
                "periods", new Object[]{
                        Map.of("startTime", "08:00", "endTime", "08:30", "total", 6),
                        Map.of("startTime", "08:30", "endTime", "09:00", "total", 6)},
                "priceItemCode", "GH-PT");

        api.post(admin, "/api/schedule/slots/batch", batch).andExpect(jsonPath("$.data").value(4));
        api.post(admin, "/api/schedule/slots/batch", batch).andExpect(jsonPath("$.data").value(0));

        api.get(admin, "/api/schedule/slots", "deptId", 102, "dateFrom", date, "dateTo", date.plusDays(1))
                .andExpect(jsonPath("$.data.total").value(4));
        api.post(cashier, "/api/schedule/slots/batch", batch).andExpect(status().isForbidden());
    }

    @Test
    void patientRegistrationsAreListedNewestFirst() throws Exception {
        ScheduleSlot first = slots.create(SlotType.OUTPATIENT.code(), 5, "GH-PT");
        ScheduleSlot second = slots.create(SlotType.VACCINE.code(), 5, "YM-FW");
        register(STUDENT_PATIENT_ID, first.getId()).andExpect(status().isOk());
        register(STUDENT_PATIENT_ID, second.getId()).andExpect(status().isOk());

        api.get(cashier, "/api/schedule/registrations", "patientId", STUDENT_PATIENT_ID)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].slotType").value(SlotType.VACCINE.code()))
                .andExpect(jsonPath("$.data[1].slotType").value(SlotType.OUTPATIENT.code()));
        assertThat(registrationMapper.selectCount(Wrappers.<Registration>lambdaQuery()
                .eq(Registration::getPatientId, STUDENT_PATIENT_ID))).isGreaterThanOrEqualTo(2);
    }

    private ResultActions register(long patientId, Long slotId)
            throws Exception {
        return api.post(cashier, "/api/schedule/registrations", Map.of("patientId", patientId, "slotId", slotId));
    }

    private String redisStock(Long slotId) {
        return redis.opsForValue().get("slot:{" + slotId + "}:stock");
    }
}
