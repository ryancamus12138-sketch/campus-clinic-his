package io.github.ryancamus.clinic.audit;

import io.github.ryancamus.clinic.patient.IdDocumentsTest;
import io.github.ryancamus.clinic.support.ApiClient;
import io.github.ryancamus.clinic.support.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * M15：操作日志完整，业务程序改不了。
 * <ol>
 *   <li>完整性：一段包含登录失败、越权、业务成功与失败、敏感查看、日志查询、退出的操作，逐条对上；</li>
 *   <li>权限：应用账号对日志库的改、删、清空、删表、改表全部被数据库拒绝，也不能在业务库建表。</li>
 * </ol>
 */
@IntegrationTest
class AuditIntegrationTest {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private StringRedisTemplate redis;
    @Autowired
    private JdbcTemplate jdbc;

    private ApiClient api;

    @BeforeEach
    void setUp() {
        api = new ApiClient(mvc, objectMapper, redis);
        var failures = redis.keys("auth:fail:*");
        if (failures != null && !failures.isEmpty()) {
            redis.delete(failures);
        }
    }

    @Test
    void everySecurityRelevantActionIsRecordedInOrder() throws Exception {
        long before = maxId();
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"cashier01\",\"password\":\"wrong-password\"}"))
                .andExpect(status().isUnauthorized());
        String cashier = api.login("cashier01", ApiClient.STAFF_PASSWORD);
        String doctor = api.login("doctor01", ApiClient.STAFF_PASSWORD);
        Map<String, Object> patient = patientBody();
        api.post(doctor, "/api/patients", patient).andExpect(status().isForbidden());
        api.get(doctor, "/api/audit/logs").andExpect(status().isForbidden());
        String patientId = api.data(api.post(cashier, "/api/patients", patient).andExpect(status().isOk()))
                .get("id").asString();
        api.post(cashier, "/api/patients", patient).andExpect(status().isConflict());
        api.get(cashier, "/api/patients/" + patientId + "/sensitive", "reason", "核对身份").andExpect(status().isOk());
        api.get(cashier, "/api/patients", "keyword", "P-not-exist").andExpect(status().isOk());
        String auditor = api.login("auditor01", ApiClient.STAFF_PASSWORD);
        api.get(auditor, "/api/audit/logs", "username", "cashier01").andExpect(status().isOk());
        api.post(cashier, "/api/auth/logout", Map.of()).andExpect(status().isOk());
        mvc.perform(post("/api/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                .content("{\"refreshToken\":\"stolen-or-replayed\"}")).andExpect(status().isUnauthorized());

        List<String> expected = List.of(
                "LOGIN|cashier01|0", "LOGIN|cashier01|1", "LOGIN|doctor01|1",
                "Patient.create|doctor01|0", "Audit.logs|doctor01|0",
                "Patient.create|cashier01|1", "Patient.create|cashier01|0",
                "PATIENT_SENSITIVE_VIEW|cashier01|1",
                "LOGIN|auditor01|1", "AUDIT_QUERY|auditor01|1",
                "LOGOUT|cashier01|1", "TOKEN_REFRESH|null|0");
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT action, username, success, http_status, "
                + "error_code, client_ip, trace_id FROM clinic_audit.audit_log WHERE id > ? ORDER BY id", before);
        List<String> actual = rows.stream()
                .map(r -> r.get("action") + "|" + r.get("username") + "|" + r.get("success")).toList();
        assertThat(actual).containsExactlyElementsOf(expected);
        assertThat(rows).allSatisfy(r -> {
            assertThat(r.get("client_ip")).isNotNull();
            assertThat(r.get("trace_id")).isNotNull();
        });
        assertThat(rows.get(3)).containsEntry("http_status", 403);
        assertThat(rows.get(6)).containsEntry("error_code", 21005);
        System.out.printf("[M15] completeness: expected=%d recorded=%d matched=%s%n", expected.size(), rows.size(),
                actual.equals(expected));
    }

    @Test
    void applicationAccountCannotAlterAuditData() {
        jdbc.queryForObject("SELECT COUNT(*) FROM clinic_audit.audit_log", Long.class);
        List<String> attempts = List.of(
                "UPDATE clinic_audit.audit_log SET username = 'x' WHERE id > 0",
                "DELETE FROM clinic_audit.audit_log WHERE id > 0",
                "TRUNCATE TABLE clinic_audit.audit_log",
                "DROP TABLE clinic_audit.audit_log",
                "ALTER TABLE clinic_audit.audit_log ADD COLUMN x INT",
                "CREATE TABLE clinic.shadow (id INT)");
        int denied = 0;
        for (String sql : attempts) {
            try {
                jdbc.execute(sql);
            } catch (DataAccessException e) {
                if (e.getMostSpecificCause().getMessage().contains("command denied")) {
                    denied++;
                }
            }
        }
        System.out.printf("[M15] privileges: attempts=%d denied=%d%n", attempts.size(), denied);
        assertThat(denied).isEqualTo(attempts.size());
    }

    // ------------------------------------------------------------------ 辅助方法

    private long maxId() {
        Long id = jdbc.queryForObject("SELECT COALESCE(MAX(id), 0) FROM clinic_audit.audit_log", Long.class);
        return id == null ? 0 : id;
    }

    private static Map<String, Object> patientBody() {
        String body = "110101" + "20030304" + String.format("%03d", (int) (Math.random() * 900) + 101);
        String idNo = body + IdDocumentsTest.check(body);
        int gender = (body.charAt(16) - '0') % 2 == 1 ? 1 : 2;
        Map<String, Object> m = new HashMap<>();
        m.put("campusId", 1);
        m.put("name", "审计测试");
        m.put("gender", gender);
        m.put("birthDate", LocalDate.of(2003, 3, 4).toString());
        m.put("patientType", 3);
        m.put("insuranceType", 0);
        m.put("idType", 1);
        m.put("idNo", idNo);
        m.put("phone", "137" + String.format("%08d", (int) (Math.random() * 100_000_000)));
        return m;
    }
}
