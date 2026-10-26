package io.github.ryancamus.clinic.charge;

import io.github.ryancamus.clinic.charge.api.PriceQueryApi;
import io.github.ryancamus.clinic.support.ApiClient;
import io.github.ryancamus.clinic.support.IntegrationTest;
import io.github.ryancamus.clinic.support.MutableClock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * M22：调价按生效时间切换。提前录入调价，到点之前划价按原价，到点之后按新价；可以撤销尚未生效的调价。
 */
@IntegrationTest
class PriceDictIntegrationTest {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private StringRedisTemplate redis;
    @Autowired
    private PriceQueryApi priceQueryApi;
    @Autowired
    private MutableClock clock;

    private ApiClient api;
    private String finance;

    @BeforeEach
    void setUp() throws Exception {
        api = new ApiClient(mvc, objectMapper, redis);
        finance = api.login("finance01", ApiClient.STAFF_PASSWORD);
    }

    @AfterEach
    void resetClock() {
        clock.reset();
    }

    @Test
    void scheduledPriceChangeSwitchesAtItsEffectiveTime() throws Exception {
        String code = "JC-T" + (System.nanoTime() % 1_000_000);
        api.post(finance, "/api/dict/price-items", Map.of("itemCode", code, "itemName", "测试检查", "itemType", 4,
                "unit", "次", "unitPrice", "20.00", "insuranceClass", 1, "insuranceCode", "250101001"))
                .andExpect(status().isOk());
        api.post(finance, "/api/dict/price-items", Map.of("itemCode", code, "itemName", "重复", "itemType", 4,
                "unit", "次", "unitPrice", "1.00", "insuranceClass", 1)).andExpect(jsonPath("$.code").value(30021));

        LocalDateTime effective = LocalDateTime.now(clock).plusDays(1).withHour(0).withMinute(0).withSecond(0).withNano(0);
        JsonNode scheduled = api.data(api.post(finance, "/api/dict/price-items/" + code + "/versions",
                Map.of("unitPrice", "25.00", "insuranceClass", 1, "effectiveFrom", effective.toString()))
                .andExpect(r -> assertThat(r.getResponse().getContentAsString()).contains("\"code\":0"))
                .andExpect(status().isOk()));
        assertThat(scheduled.get("current").get("unitPrice").asString()).isEqualTo("20.00");
        assertThat(scheduled.get("pending").get("unitPrice").asString()).isEqualTo("25.00");
        // 同时只能有一个待生效调价；生效时间不能早于现在
        api.post(finance, "/api/dict/price-items/" + code + "/versions", Map.of("unitPrice", "26.00",
                "insuranceClass", 1, "effectiveFrom", effective.plusDays(1).toString())).andExpect(jsonPath("$.code").value(30023));

        String before = priceQueryApi.findEffective(code, LocalDateTime.now(clock)).orElseThrow().unitPrice().toString();
        clock.travelTo(effective.plusMinutes(1));
        String after = priceQueryApi.findEffective(code, LocalDateTime.now(clock)).orElseThrow().unitPrice().toString();
        assertThat(before).isEqualTo("20.00");
        assertThat(after).isEqualTo("25.00");
        // 时钟拨到第二天，原访问令牌已过期
        finance = api.login("finance01", ApiClient.STAFF_PASSWORD);

        // 撤销：再录一个调价并撤销，当前价格不变
        api.post(finance, "/api/dict/price-items/" + code + "/versions", Map.of("unitPrice", "30.00",
                "insuranceClass", 1, "effectiveFrom", effective.plusDays(7).toString())).andExpect(status().isOk());
        mvc.perform(delete("/api/dict/price-items/" + code + "/versions/pending").header("Authorization", finance))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.current.unitPrice").value("25.00"));
        clock.travelTo(effective.plusDays(8));
        assertThat(priceQueryApi.findEffective(code, LocalDateTime.now(clock)).orElseThrow().unitPrice().toString())
                .isEqualTo("25.00");
        System.out.printf("[M22] price versions: before=%s after=%s pendingCancelled=true singlePending=true%n",
                before, after);
    }
}
