package io.github.ryancamus.clinic.pharmacy;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import io.github.ryancamus.clinic.support.ApiClient;
import io.github.ryancamus.clinic.support.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 药品目录维护：医保编码对照、编码唯一、按医保编码检索；只有药师和管理员能维护。
 */
@IntegrationTest
class DrugDictIntegrationTest {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private StringRedisTemplate redis;

    private ApiClient api;
    private String pharmacist;

    @BeforeEach
    void setUp() throws Exception {
        api = new ApiClient(mvc, objectMapper, redis);
        pharmacist = api.login("pharm01", ApiClient.STAFF_PASSWORD);
    }

    @Test
    void drugDictionaryMaintainsInsuranceCodes() throws Exception {
        String code = "YP-T" + IdWorker.getId() % 1_000_000;
        Map<String, Object> body = new HashMap<>(Map.ofEntries(
                Map.entry("drugCode", code), Map.entry("insuranceCode", "XA02BCC012A001010100000"),
                Map.entry("name", "测试用奥美拉唑肠溶胶囊"), Map.entry("spec", "20mg×14粒"), Map.entry("unit", "盒"),
                Map.entry("goodsType", 1), Map.entry("retailPrice", "18.50"), Map.entry("insuranceClass", 1),
                Map.entry("traceRequired", 1), Map.entry("approvalNo", "国药准字H00000000"),
                Map.entry("safetyStock", 10), Map.entry("status", 1)));
        String doctor = api.login("doctor01", ApiClient.STAFF_PASSWORD);
        api.post(doctor, "/api/dict/drugs", body).andExpect(status().isForbidden());
        JsonNode created = api.data(api.post(pharmacist, "/api/dict/drugs", body).andExpect(status().isOk()));
        api.post(pharmacist, "/api/dict/drugs", body).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(40012));
        body.put("retailPrice", "19.00");
        api.put(pharmacist, "/api/dict/drugs/" + created.get("id").asString(), body)
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.retailPrice").value("19.00"));
        JsonNode found = api.data(api.get(pharmacist, "/api/dict/drugs", "keyword", "XA02BCC012A001010100000"));
        assertThat(found.get("list").findValuesAsString("drugCode")).containsExactly(code);
    }
}
