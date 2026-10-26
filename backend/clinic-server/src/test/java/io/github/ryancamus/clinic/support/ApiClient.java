package io.github.ryancamus.clinic.support;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;
import java.util.Set;

/**
 * 集成测试用的 HTTP 客户端：登录取令牌、带令牌发请求。
 */
public class ApiClient {

    public static final String STAFF_PASSWORD = "Clinic@2026";

    private final MockMvc mvc;
    private final ObjectMapper objectMapper;
    private final StringRedisTemplate redis;

    public ApiClient(MockMvc mvc, ObjectMapper objectMapper, StringRedisTemplate redis) {
        this.mvc = mvc;
        this.objectMapper = objectMapper;
        this.redis = redis;
    }

    public String login(String username, String password) throws Exception {
        Set<String> keys = redis.keys("auth:fail:*");
        if (keys != null && !keys.isEmpty()) {
            redis.delete(keys);
        }
        String body = mvc.perform(MockMvcRequestBuilders.post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("username", username, "password", password))))
                .andReturn().getResponse().getContentAsString();
        return "Bearer " + objectMapper.readTree(body).at("/data/accessToken").asString();
    }

    public ResultActions get(String token, String url, Object... params) throws Exception {
        var request = MockMvcRequestBuilders.get(url)
                .header("Authorization", token);
        for (int i = 0; i + 1 < params.length; i += 2) {
            request.param(String.valueOf(params[i]), String.valueOf(params[i + 1]));
        }
        return mvc.perform(request);
    }

    public ResultActions post(String token, String url, Object body) throws Exception {
        return mvc.perform(MockMvcRequestBuilders.post(url)
                .header("Authorization", token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body == null ? "{}" : json(body)));
    }

    public ResultActions put(String token, String url, Object body) throws Exception {
        return mvc.perform(MockMvcRequestBuilders.put(url)
                .header("Authorization", token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(body)));
    }

    public JsonNode data(ResultActions actions) throws Exception {
        return objectMapper.readTree(actions.andReturn().getResponse().getContentAsString()).get("data");
    }

    private String json(Object value) throws Exception {
        return objectMapper.writeValueAsString(value);
    }
}
