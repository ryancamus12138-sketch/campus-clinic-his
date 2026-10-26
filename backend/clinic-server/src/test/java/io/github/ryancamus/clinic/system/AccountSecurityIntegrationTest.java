package io.github.ryancamus.clinic.system;

import io.github.ryancamus.clinic.support.ApiClient;
import io.github.ryancamus.clinic.support.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * M17：账号安全。
 * <ol>
 *   <li>口令复杂度：弱口令全部拒绝；</li>
 *   <li>新建账号与重置口令：临时口令只显示一次，首次登录必须修改；</li>
 *   <li>账号管理：管理员与审计员不能是同一人、不能锁死自己、连续输错锁定与解锁、停用。</li>
 * </ol>
 */
@IntegrationTest
class AccountSecurityIntegrationTest {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private StringRedisTemplate redis;
    @Autowired
    private JdbcTemplate jdbc;

    private ApiClient api;
    private String admin;

    @BeforeEach
    void setUp() throws Exception {
        api = new ApiClient(mvc, objectMapper, redis);
        admin = api.login("admin", "Admin@2026");
    }

    @Test
    void weakPasswordsAreRejected() throws Exception {
        Created u = createUser(List.of(2L));
        String token = firstLogin(u, "Strong#Pass2026");
        List<String> weak = List.of("Ab1#", "alllowercase123", "ALLUPPER-ONLY", u.username() + "Aa#1xyz",
                "Password#2026", "Aa123456#x");
        int rejected = 0;
        for (String w : weak) {
            int status = api.post(token, "/api/auth/password", Map.of("oldPassword", "Strong#Pass2026",
                    "newPassword", w)).andReturn().getResponse().getStatus();
            rejected += status == 400 ? 1 : 0;
        }
        api.post(token, "/api/auth/password", Map.of("oldPassword", "wrong-old", "newPassword", "Another#Pass2026"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(10008));
        api.post(token, "/api/auth/password", Map.of("oldPassword", "Strong#Pass2026", "newPassword", "Another#Pass2026"))
                .andExpect(status().isOk());
        assertThat(loginRaw(u.username(), "Another#Pass2026").path("accessToken").isMissingNode()).isFalse();
        System.out.printf("[M17] password policy: weak=%d rejected=%d%n", weak.size(), rejected);
        assertThat(rejected).isEqualTo(weak.size());
    }

    @Test
    void temporaryPasswordsMustBeChangedBeforeAnyToken() throws Exception {
        Created u = createUser(List.of(2L));
        JsonNode step = loginRaw(u.username(), u.password());
        assertThat(step.get("step").asString()).isEqualTo("PASSWORD_CHANGE");
        assertThat(step.path("accessToken").isMissingNode()).isTrue();
        String challenge = step.get("challenge").asString();
        // 新口令不合规时凭证不作废，可以重试
        expired(challenge, "short").andExpect(status().isBadRequest());
        JsonNode done = api.data(expired(challenge, "Changed#Pass2026").andExpect(status().isOk()));
        assertThat(done.get("accessToken").asString()).isNotBlank();
        // 凭证只能用一次
        expired(challenge, "Again#Pass2026x").andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(10006));

        // 管理员重置口令：同样只显示一次，必须修改
        JsonNode reset = api.data(api.post(admin, "/api/system/users/" + u.id() + "/reset-password", null)
                .andExpect(status().isOk()));
        JsonNode afterReset = loginRaw(u.username(), reset.get("temporaryPassword").asString());
        assertThat(afterReset.get("step").asString()).isEqualTo("PASSWORD_CHANGE");
        System.out.println("[M17] temporary/reset passwords: change enforced before tokens, challenge single-use");
    }

    @Test
    void accountManagementGuardsSeparationOfDutiesAndSelfLockout() throws Exception {
        Map<String, Object> both = Map.of("username", "u" + suffix(), "nickname", "兼任", "campusId", 1,
                "roleIds", List.of(1, 7));
        api.post(admin, "/api/system/users", both).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(10011));
        api.post(admin, "/api/system/users", Map.of("username", "u" + suffix(), "nickname", "患者角色",
                "campusId", 1, "roleIds", List.of(6))).andExpect(jsonPath("$.code").value(10018));
        api.put(admin, "/api/system/users/1", Map.of("nickname", "系统管理员", "campusId", 1, "roleIds", List.of(2),
                "status", 1)).andExpect(jsonPath("$.code").value(10012));
        api.put(admin, "/api/system/users/1", Map.of("nickname", "系统管理员", "campusId", 1, "roleIds", List.of(1),
                "status", 0)).andExpect(jsonPath("$.code").value(10012));
        // 患者不再登录本系统，原演示学生账号已删除
        api.put(admin, "/api/system/users/6", Map.of("nickname", "演示学生", "campusId", 1, "roleIds", List.of(2),
                "status", 1)).andExpect(jsonPath("$.code").value(10009));

        // 锁定与解锁
        Created u = createUser(List.of(2L));
        firstLogin(u, "Lock#Pass2026");
        for (int i = 0; i < 5; i++) {
            loginStatus(u.username(), "wrong-password");
        }
        assertThat(loginStatus(u.username(), "Lock#Pass2026")).isEqualTo(423);
        api.post(admin, "/api/system/users/" + u.id() + "/unlock", null).andExpect(status().isOk());
        assertThat(loginStatus(u.username(), "Lock#Pass2026")).isEqualTo(200);

        // 修改角色：停用后无法登录
        api.put(admin, "/api/system/users/" + u.id(), Map.of("nickname", "已停用", "campusId", 1, "roleIds", List.of(3),
                "status", 0)).andExpect(status().isOk());
        assertThat(loginStatus(u.username(), "Lock#Pass2026")).isEqualTo(403);
        System.out.println("[M17] account management: admin+auditor rejected, self-lockout rejected, unlock ok, disable ok");
    }

    // ------------------------------------------------------------------ helpers

    record Created(String id, String username, String password) {
    }

    private Created createUser(List<Long> roleIds) throws Exception {
        String username = "u" + suffix();
        JsonNode created = api.data(api.post(admin, "/api/system/users", Map.of("username", username,
                "nickname", "测试员工", "campusId", 1, "deptId", 107, "roleIds", roleIds)).andExpect(status().isOk()));
        return new Created(created.get("id").asString(), username, created.get("temporaryPassword").asString());
    }

    /** 首次登录：用临时口令登录并改成指定口令，返回访问令牌 */
    private String firstLogin(Created u, String newPassword) throws Exception {
        JsonNode step = loginRaw(u.username(), u.password());
        JsonNode done = api.data(expired(step.get("challenge").asString(), newPassword).andExpect(status().isOk()));
        return "Bearer " + done.get("accessToken").asString();
    }

    private JsonNode loginRaw(String username, String password) throws Exception {
        return objectMapper.readTree(mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("username", username, "password", password))))
                .andReturn().getResponse().getContentAsString()).get("data");
    }

    private int loginStatus(String username, String password) throws Exception {
        return mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("username", username, "password", password))))
                .andReturn().getResponse().getStatus();
    }

    private ResultActions expired(String challenge, String password) throws Exception {
        return mvc.perform(post("/api/auth/password/expired").contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("challenge", challenge, "newPassword", password))));
    }

    private static String suffix() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    }
}
