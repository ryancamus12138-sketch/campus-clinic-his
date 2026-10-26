package io.github.ryancamus.clinic.system;

import io.github.ryancamus.clinic.support.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.RequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@IntegrationTest
class AuthIntegrationTest {

    private static final String STAFF_PASSWORD = "Clinic@2026";

    @Autowired
    private MockMvc mvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private StringRedisTemplate redis;

    @BeforeEach
    void clearLoginFailures() {
        Set<String> keys = redis.keys("auth:fail:*");
        if (keys != null && !keys.isEmpty()) {
            redis.delete(keys);
        }
    }

    @Test
    void loginReturnsTokensAndMeReflectsPermissions() throws Exception {
        JsonNode tokens = login("cashier01", STAFF_PASSWORD);

        mvc.perform(get("/api/auth/me").header("Authorization", bearer(tokens)))
                .andExpect(status().isOk())
                .andExpect(header().exists("X-Trace-Id"))
                .andExpect(jsonPath("$.data.username").value("cashier01"))
                .andExpect(jsonPath("$.data.userId").isString())
                .andExpect(jsonPath("$.data.roles[0]").value("CASHIER"))
                .andExpect(jsonPath("$.data.permissions").isArray());
    }

    @Test
    void wrongPasswordIsRejectedWithoutRevealingWhichFieldWasWrong() throws Exception {
        mvc.perform(loginRequest("cashier01", "wrong-password"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(10001));
        mvc.perform(loginRequest("no-such-user", "whatever"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(10001));
    }

    @Test
    void accountIsLockedAfterFiveFailures() throws Exception {
        for (int i = 0; i < 5; i++) {
            mvc.perform(loginRequest("pharm01", "wrong-password")).andExpect(status().isUnauthorized());
        }
        // 第 6 次即使密码正确也被拒绝
        mvc.perform(loginRequest("pharm01", STAFF_PASSWORD))
                .andExpect(status().is(423))
                .andExpect(jsonPath("$.code").value(10002));
    }

    @Test
    void refreshTokenIsSingleUse() throws Exception {
        JsonNode tokens = login("doctor01", STAFF_PASSWORD);
        String refreshToken = tokens.get("refreshToken").asString();

        mvc.perform(refreshRequest(refreshToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accessToken").isNotEmpty());
        // 同一个刷新令牌第二次使用失效，防止被盗后重放
        mvc.perform(refreshRequest(refreshToken))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(10004));
    }

    @Test
    void logoutRevokesAccessToken() throws Exception {
        JsonNode tokens = login("finance01", STAFF_PASSWORD);

        mvc.perform(post("/api/auth/logout").header("Authorization", bearer(tokens))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("refreshToken", tokens.get("refreshToken").asString()))))
                .andExpect(status().isOk());

        mvc.perform(get("/api/auth/me").header("Authorization", bearer(tokens)))
                .andExpect(status().isUnauthorized());
        mvc.perform(refreshRequest(tokens.get("refreshToken").asString()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void permissionsAreEnforcedPerEndpoint() throws Exception {
        JsonNode doctor = login("doctor01", STAFF_PASSWORD);
        mvc.perform(get("/api/system/users").header("Authorization", bearer(doctor)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));

        JsonNode admin = login("admin", "Admin@2026");
        mvc.perform(get("/api/system/users").param("page", "1").param("size", "5")
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").isNumber())
                .andExpect(jsonPath("$.data.list.length()").value(5));
    }

    @Test
    void selectedIdentityMustBeHeldByTheAccountAndNeverGrantsPermissions() throws Exception {
        // 登录页可选身份来自系统中启用的角色；不再提供演示账号信息
        mvc.perform(get("/api/auth/options"))
                .andExpect(jsonPath("$.data.orgName").value("实践大学校医院"))
                .andExpect(jsonPath("$.data.roles[?(@.code == 'CASHIER')].name").value("收费员"))
                .andExpect(jsonPath("$.data.roles[?(@.code == 'FINANCE')]").isNotEmpty())
                .andExpect(jsonPath("$.data.roles[?(@.code == 'PATIENT')]").isEmpty())
                .andExpect(jsonPath("$.data.demo").doesNotExist());

        // 收费员账号选择“财务”身份：口令正确也不发令牌
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("username", "cashier01", "password", STAFF_PASSWORD, "role", "FINANCE"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(10022))
                .andExpect(jsonPath("$.data").doesNotExist());
        // 选择不存在的身份同样拒绝；格式不对是参数错误
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("username", "cashier01", "password", STAFF_PASSWORD, "role", "SUPER_ADMIN"))))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("username", "cashier01", "password", STAFF_PASSWORD, "role", "finance"))))
                .andExpect(status().isBadRequest());
        // 口令错误时仍按口令错误处理，不提示身份
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("username", "cashier01", "password", "Wrong@2026x", "role", "FINANCE"))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(10001));

        // 身份匹配：登录成功，权限仍是账号自身的权限（收费员看不到财务报表）
        MvcResult ok = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("username", "cashier01", "password", STAFF_PASSWORD, "role", "CASHIER"))))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode tokens = objectMapper.readTree(ok.getResponse().getContentAsString()).get("data");
        mvc.perform(get("/api/auth/me").header("Authorization", bearer(tokens)))
                .andExpect(jsonPath("$.data.roles[0]").value("CASHIER"));
        mvc.perform(get("/api/finance/reports/summary").param("from", "2026-10-01").param("to", "2026-10-02")
                        .header("Authorization", bearer(tokens)))
                .andExpect(status().isForbidden());
    }

    @Test
    void requestsWithoutTokenGetUnifiedUnauthorizedBody() throws Exception {
        mvc.perform(get("/api/auth/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401))
                .andExpect(jsonPath("$.traceId").isNotEmpty());
    }

    private JsonNode login(String username, String password) throws Exception {
        MvcResult result = mvc.perform(loginRequest(username, password))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode data = objectMapper.readTree(result.getResponse().getContentAsString()).get("data");
        assertThat(data.get("tokenType").asString()).isEqualTo("Bearer");
        return data;
    }

    private RequestBuilder loginRequest(String username, String password)
            throws Exception {
        return post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("username", username, "password", password)));
    }

    private RequestBuilder refreshRequest(String refreshToken) throws Exception {
        return post("/api/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("refreshToken", refreshToken)));
    }

    private String json(Object value) throws Exception {
        return objectMapper.writeValueAsString(value);
    }

    private static String bearer(JsonNode tokens) {
        return "Bearer " + tokens.get("accessToken").asString();
    }
}
