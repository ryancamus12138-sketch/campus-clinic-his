package io.github.ryancamus.clinic.patient;

import io.github.ryancamus.clinic.common.util.Masks;
import io.github.ryancamus.clinic.support.ApiClient;
import io.github.ryancamus.clinic.support.IntegrationTest;
import io.github.ryancamus.clinic.support.RootJdbc;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.mysql.MySQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * M16：敏感数据保护。
 * <ol>
 *   <li>建档 200 人，证件号与出生日期、性别交叉核对，重复建档被拒；</li>
 *   <li>全库扫描：两个库的所有文本和二进制列，以及应用日志输出，都找不到任何一个证件号或手机号明文；</li>
 *   <li>盲索引检索：按证件号、手机号都能精确找到本人；</li>
 *   <li>接口默认脱敏，完整值需要单独权限和原因，查看行为进操作日志。</li>
 * </ol>
 */
@IntegrationTest
@ExtendWith(OutputCaptureExtension.class)
class PatientSecurityIntegrationTest {

    private static final int PATIENTS = 200;
    private static final String[] REGIONS = {"110101", "120101", "370102", "510104", "610103", "650102"};

    @Autowired
    private MockMvc mvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private StringRedisTemplate redis;
    @Autowired
    private MySQLContainer mysql;
    @Autowired
    private JdbcTemplate jdbc;

    private ApiClient api;
    private String cashier;

    @BeforeEach
    void setUp() throws Exception {
        api = new ApiClient(mvc, objectMapper, redis);
        cashier = api.login("cashier01", ApiClient.STAFF_PASSWORD);
    }

    @Test
    void sensitiveDataIsEncryptedSearchableMaskedAndAudited(CapturedOutput output) throws Exception {
        Random random = new Random(20261025);
        List<Person> people = new ArrayList<>();
        Set<String> used = new HashSet<>();
        while (people.size() < PATIENTS) {
            Person p = Person.random(random, people.size());
            if (used.add(p.idNo) && used.add(p.phone)) {
                people.add(p);
            }
        }
        Map<String, String> idToPatient = new HashMap<>();
        for (Person p : people) {
            JsonNode created = api.data(api.post(cashier, "/api/patients", p.body())
                    .andExpect(status().isOk()));
            idToPatient.put(p.idNo, created.get("id").asString());
            assertThat(created.get("idNoMasked").asString()).isEqualTo(Masks.idNo(p.idNo));
            assertThat(created.get("phoneMasked").asString()).isEqualTo(Masks.phone(p.phone));
        }

        // 重复建档、号码与出生日期不符、校验位错误
        Person first = people.get(0);
        api.post(cashier, "/api/patients", first.withSchoolNo("X0000001").body())
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value(21005));
        api.post(cashier, "/api/patients", first.withBirth(first.birth.plusDays(1)).withSchoolNo("X0000002").body())
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(21003));
        String badCheck = first.idNo.substring(0, 17) + (first.idNo.charAt(17) == '0' ? '1' : '0');
        api.post(cashier, "/api/patients", first.withId(badCheck).withSchoolNo("X0000003").body())
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(21002));

        // 盲索引检索：证件号（含小写 x、空格）、手机号
        int foundById = 0;
        int foundByPhone = 0;
        for (Person p : people) {
            JsonNode byId = api.data(api.get(cashier, "/api/patients", "keyword", " " + p.idNo.toLowerCase() + " "));
            if (byId.size() == 1 && byId.get(0).get("id").asString().equals(idToPatient.get(p.idNo))) {
                foundById++;
            }
            JsonNode byPhone = api.data(api.get(cashier, "/api/patients", "keyword", p.phone));
            if (byPhone.size() == 1 && byPhone.get(0).get("id").asString().equals(idToPatient.get(p.idNo))) {
                foundByPhone++;
            }
        }
        assertThat(foundById).isEqualTo(PATIENTS);
        assertThat(foundByPhone).isEqualTo(PATIENTS);

        // 全库扫描明文：两个库所有字符、文本、二进制列，再加上应用日志输出
        JdbcTemplate root = RootJdbc.of(mysql);
        List<Map<String, Object>> columns = root.queryForList("SELECT table_schema, table_name, column_name "
                + "FROM information_schema.columns WHERE table_schema IN ('clinic', 'clinic_audit') AND data_type IN "
                + "('char','varchar','text','mediumtext','longtext','json','blob','mediumblob','longblob','binary','varbinary')");
        List<String> samples = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            samples.add(people.get(i * 20).idNo);
            samples.add(people.get(i * 20).phone);
        }
        int plaintextHits = 0;
        for (Map<String, Object> c : columns) {
            String sql = "SELECT COUNT(*) FROM `%s`.`%s` WHERE `%s` LIKE ?".formatted(c.get("TABLE_SCHEMA"),
                    c.get("TABLE_NAME"), c.get("COLUMN_NAME"));
            for (String value : samples) {
                Integer n = root.queryForObject(sql, Integer.class, "%" + value + "%");
                plaintextHits += n == null ? 0 : n;
            }
        }
        long logHits = samples.stream().filter(output.getAll()::contains).count();
        assertThat(plaintextHits).isZero();
        assertThat(logHits).isZero();

        // 默认脱敏；完整值需要单独权限和原因，并留审计
        String patientId = idToPatient.get(first.idNo);
        JsonNode detail = api.data(api.get(cashier, "/api/patients/" + patientId).andExpect(status().isOk()));
        assertThat(detail.get("idNoMasked").asString()).isEqualTo(Masks.idNo(first.idNo));
        String doctor = api.login("doctor01", ApiClient.STAFF_PASSWORD);
        api.get(doctor, "/api/patients/" + patientId + "/sensitive", "reason", "核对身份")
                .andExpect(status().isForbidden());
        api.get(cashier, "/api/patients/" + patientId + "/sensitive", "reason", " ")
                .andExpect(status().isBadRequest());
        Long auditBefore = jdbc.queryForObject("SELECT COALESCE(MAX(id), 0) FROM clinic_audit.audit_log", Long.class);
        JsonNode full = api.data(api.get(cashier, "/api/patients/" + patientId + "/sensitive", "reason", "医保结算核对身份")
                .andExpect(status().isOk()));
        assertThat(full.get("idNo").asString()).isEqualTo(first.idNo);
        assertThat(full.get("phone").asString()).isEqualTo(first.phone);
        List<Map<String, Object>> audit = jdbc.queryForList(
                "SELECT action, username, target, detail FROM clinic_audit.audit_log WHERE id > ? ORDER BY id", auditBefore);
        assertThat(audit).hasSize(1);
        assertThat(audit.get(0)).containsEntry("action", "PATIENT_SENSITIVE_VIEW")
                .containsEntry("username", "cashier01").containsEntry("target", "patientId=" + patientId)
                .containsEntry("detail", "reason=医保结算核对身份");

        System.out.printf("[M16] patients=%d searchById=%d/%d searchByPhone=%d/%d scannedColumns=%d samples=%d "
                        + "plaintextHits=%d logHits=%d revealAudited=%d%n",
                PATIENTS, foundById, PATIENTS, foundByPhone, PATIENTS, columns.size(), samples.size(), plaintextHits,
                logHits, audit.size());
    }

    /** 随机生成的测试身份（地区码取几个常见城区，号码不对应真实个人） */
    record Person(String name, int gender, LocalDate birth, String idNo, String phone, String schoolNo) {

        static Person random(Random r, int seq) {
            LocalDate birth = LocalDate.of(1998, 1, 1).plusDays(r.nextInt(365 * 8));
            int order = r.nextInt(999) + 1;
            String body = REGIONS[r.nextInt(REGIONS.length)] + birth.toString().replace("-", "")
                    + String.format("%03d", order);
            String idNo = body + IdDocumentsTest.check(body);
            int gender = order % 2 == 1 ? 1 : 2;
            String phone = "139" + String.format("%08d", r.nextInt(100_000_000));
            return new Person("测试患者" + seq, gender, birth, idNo, phone, String.format("M%07d", seq));
        }

        Person withSchoolNo(String no) {
            return new Person(name, gender, birth, idNo, phone, no);
        }

        Person withBirth(LocalDate b) {
            return new Person(name, gender, b, idNo, phone, schoolNo);
        }

        Person withId(String id) {
            return new Person(name, gender, birth, id, phone, schoolNo);
        }

        Map<String, Object> body() {
            Map<String, Object> m = new HashMap<>();
            m.put("campusId", 1);
            m.put("name", name);
            m.put("gender", gender);
            m.put("birthDate", birth.toString());
            m.put("patientType", 1);
            m.put("schoolNo", schoolNo);
            m.put("insuranceType", 1);
            m.put("idType", 1);
            m.put("idNo", idNo);
            m.put("phone", phone);
            return m;
        }
    }
}
