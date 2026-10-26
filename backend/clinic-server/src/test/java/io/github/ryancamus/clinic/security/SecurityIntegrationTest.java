package io.github.ryancamus.clinic.security;

import io.github.ryancamus.clinic.charge.domain.dto.ChargeCreateDTO;
import io.github.ryancamus.clinic.charge.domain.dto.ChargeItemDTO;
import io.github.ryancamus.clinic.charge.service.ChargeOrderService;
import io.github.ryancamus.clinic.charge.service.CounterCheckoutService;
import io.github.ryancamus.clinic.framework.security.ClinicSecurityProperties;
import io.github.ryancamus.clinic.support.ApiClient;
import io.github.ryancamus.clinic.support.IntegrationTest;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M10：越权访问。
 * <ol>
 *   <li>接口清单自动生成：从 Spring MVC 取出全部接口，未登录逐个调用，除放行清单外必须 401；</li>
 *   <li>角色矩阵：读取每个接口的 @PreAuthorize，各员工角色的演示账号逐个调用自己“没有权限”的接口，不允许出现 2xx；</li>
 *   <li>水平越权：收费员的班次只能由本人交班，管理员也交不了别人的班；收费员只看得到自己的班次；</li>
 *   <li>令牌篡改：改权限声明、alg=none、过期、他人密钥签名，一律 401；</li>
 *   <li>注入：检索参数中的 SQL 片段不会报错也不会命中数据。</li>
 * </ol>
 */
@IntegrationTest
class SecurityIntegrationTest {

    private static final Pattern AUTHORITY = Pattern.compile("'([a-z]+(?::[a-z]+)*)'");
    private static final Pattern PATH_VARIABLE = Pattern.compile("\\{[^}]+}");

    @Autowired
    private MockMvc mvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private StringRedisTemplate redis;
    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping handlerMapping;
    @Autowired
    private ClinicSecurityProperties securityProperties;
    @Autowired
    private CounterCheckoutService checkout;
    @Autowired
    private ChargeOrderService chargeOrderService;
    @Value("${clinic.security.jwt.secret}")
    private String jwtSecret;

    private ApiClient api;

    @BeforeEach
    void setUp() {
        api = new ApiClient(mvc, objectMapper, redis);
    }

    // ------------------------------------------------------------------ 1. 未登录

    @Test
    void everyEndpointRequiresLoginExceptTheWhitelist() throws Exception {
        AntPathMatcher matcher = new AntPathMatcher();
        int checked = 0;
        List<String> leaks = new ArrayList<>();
        for (Endpoint e : endpoints()) {
            boolean permitted = securityProperties.permitUrls().stream().anyMatch(p -> matcher.match(p, e.path()));
            if (permitted) {
                continue;
            }
            int status = call(e, null).getResponse().getStatus();
            checked++;
            if (status != 401) {
                leaks.add(e + " -> " + status);
            }
        }
        System.out.printf("[M10] anonymous: endpoints=%d all401=%s%n", checked, leaks.isEmpty());
        assertThat(checked).isGreaterThan(40);
        assertThat(leaks).isEmpty();
    }

    // ------------------------------------------------------------------ 2. 角色矩阵

    @Test
    void everyRoleIsRejectedFromEndpointsOutsideItsPermissions() throws Exception {
        Map<String, Set<String>> users = new LinkedHashMap<>();
        Map<String, String> tokens = new LinkedHashMap<>();
        for (String username : List.of("cashier01", "pharm01", "doctor01", "finance01", "auditor01")) {
            String token = api.login(username, ApiClient.STAFF_PASSWORD);
            tokens.put(username, token);
            Set<String> perms = new HashSet<>();
            api.data(api.get(token, "/api/auth/me")).get("permissions").forEach(p -> perms.add(p.asString()));
            users.put(username, perms);
        }
        int checked = 0;
        Map<Integer, Integer> statuses = new TreeMap<>();
        List<String> leaks = new ArrayList<>();
        for (Endpoint e : endpoints()) {
            Set<String> required = e.authorities();
            if (required.isEmpty()) {
                continue;
            }
            for (Map.Entry<String, Set<String>> u : users.entrySet()) {
                if (required.stream().anyMatch(u.getValue()::contains)) {
                    continue;
                }
                int status = call(e, tokens.get(u.getKey())).getResponse().getStatus();
                checked++;
                statuses.merge(status, 1, Integer::sum);
                if (status < 300 || status >= 500) {
                    leaks.add(u.getKey() + " " + e + " -> " + status);
                }
            }
        }
        System.out.printf("[M10] role matrix: checks=%d statuses=%s leaks=%d%n", checked, statuses, leaks.size());
        assertThat(checked).isGreaterThan(150);
        assertThat(leaks).isEmpty();
    }

    // ------------------------------------------------------------------ 3. 水平越权

    @Test
    void staffCannotActOnAnotherStaffMembersRecords() throws Exception {
        // 收费员的班次只能由本人交班；管理员虽然有交班页面的权限，也交不了别人的班
        String cashier = api.login("cashier01", ApiClient.STAFF_PASSWORD);
        String admin = api.login("admin", "Admin@2026");
        var order = chargeOrderService.createCounterOrder(new ChargeCreateDTO(
                90002L, List.of(new ChargeItemDTO("JC-NCG", 1))));
        checkout.cashPay(order.id(), order.payableAmount(), UUID.randomUUID().toString(), 2L);
        String shiftId = api.data(api.get(cashier, "/api/finance/shifts/current")).get("id").asString();

        MvcResult attempt = mvc.perform(MockMvcRequestBuilders.post("/api/finance/shifts/" + shiftId + "/hand-over")
                .header("Authorization", admin).contentType(MediaType.APPLICATION_JSON)
                .content("{\"countedCash\":\"0.00\",\"diffNote\":\"代交\"}")).andReturn();
        assertThat(attempt.getResponse().getStatus()).isEqualTo(403);
        // 收费员的班次列表只有自己的
        JsonNode mine = api.data(api.get(cashier, "/api/finance/shifts"));
        assertThat(mine.get("list").findValuesAsString("cashierId")).containsOnly("2");
        System.out.printf("[M10] horizontal: attempts=1 blocked=1%n");
    }

    // ------------------------------------------------------------------ 4. 令牌篡改

    @Test
    void tamperedOrForgedTokensAreRejected() throws Exception {
        String staff = api.login("pharm01", ApiClient.STAFF_PASSWORD).substring("Bearer ".length());
        String[] parts = staff.split("\\.");
        Base64.Decoder dec = Base64.getUrlDecoder();
        Base64.Encoder enc = Base64.getUrlEncoder().withoutPadding();
        String payload = new String(dec.decode(parts[1]), StandardCharsets.UTF_8);

        // 改权限声明、保留原签名
        String escalated = parts[0] + "." + enc.encodeToString(payload.replace("\"pharmacy:dispense\"",
                "\"pharmacy:dispense\",\"charge:settle\",\"system:user:list\"").getBytes(StandardCharsets.UTF_8)) + "." + parts[2];
        // alg = none
        String none = enc.encodeToString("{\"alg\":\"none\"}".getBytes(StandardCharsets.UTF_8)) + "." + parts[1] + ".";
        // 别人的密钥签名
        String foreign = Jwts.builder().subject("1").issuer("campus-clinic-his").claim("perms", List.of("system:user:list"))
                .expiration(new Date(System.currentTimeMillis() + 600_000))
                .signWith(Keys.hmacShaKeyFor("another-secret-another-secret-0123456789".getBytes(StandardCharsets.UTF_8)))
                .compact();
        // 正确密钥但已过期
        String expired = Jwts.builder().subject("1").issuer("campus-clinic-his")
                .issuedAt(new Date(System.currentTimeMillis() - 7_200_000))
                .expiration(new Date(System.currentTimeMillis() - 3_600_000))
                .signWith(Keys.hmacShaKeyFor(jwtSecret.getBytes(StandardCharsets.UTF_8)))
                .compact();

        int blocked = 0;
        for (String token : List.of(escalated, none, foreign, expired, "garbage", "")) {
            int status = mvc.perform(MockMvcRequestBuilders.get("/api/system/users")
                    .header("Authorization", "Bearer " + token)).andReturn().getResponse().getStatus();
            assertThat(status).as(token).isEqualTo(401);
            blocked++;
        }
        System.out.printf("[M10] tokens: forged=%d blocked=%d%n", blocked, blocked);
    }

    // ------------------------------------------------------------------ 5. 注入

    @Test
    void sqlFragmentsInParametersAreTreatedAsPlainValues() throws Exception {
        String cashier = api.login("cashier01", ApiClient.STAFF_PASSWORD);
        List<String> payloads = List.of("' OR '1'='1", "1; DROP TABLE patient; --", "\" OR 1=1 --",
                "P00001' UNION SELECT id_card_enc FROM patient --");
        for (String p : payloads) {
            api.get(cashier, "/api/patients", "keyword", p)
                    .andExpect(r -> assertThat(r.getResponse().getStatus()).isEqualTo(200))
                    .andExpect(r -> assertThat(objectMapper.readTree(r.getResponse().getContentAsString())
                            .get("data").size()).isZero());
            api.get(cashier, "/api/charge/orders", "chargeNo", p)
                    .andExpect(r -> assertThat(r.getResponse().getStatus()).isEqualTo(200));
        }
        // 检索仍然正常工作
        assertThat(api.data(api.get(cashier, "/api/patients", "keyword", "D20210001")).size()).isEqualTo(1);
        System.out.printf("[M10] injection: payloads=%d errors=0%n", payloads.size() * 2);
    }

    // ------------------------------------------------------------------ 辅助方法

    private record Endpoint(HttpMethod method, String path, Set<String> authorities) {
        @Override
        public String toString() {
            return method + " " + path;
        }
    }

    private List<Endpoint> endpoints() {
        List<Endpoint> result = new ArrayList<>();
        for (Map.Entry<RequestMappingInfo, HandlerMethod> entry : handlerMapping.getHandlerMethods().entrySet()) {
            RequestMappingInfo info = entry.getKey();
            if (info.getPathPatternsCondition() == null) {
                continue;
            }
            Set<String> authorities = authorities(entry.getValue());
            Set<RequestMethod> methods = info.getMethodsCondition().getMethods();
            for (String pattern : info.getPathPatternsCondition().getPatternValues()) {
                if (!pattern.startsWith("/api/")) {
                    continue;
                }
                String path = PATH_VARIABLE.matcher(pattern).replaceAll("1");
                for (RequestMethod m : methods.isEmpty() ? Set.of(RequestMethod.GET) : methods) {
                    result.add(new Endpoint(HttpMethod.valueOf(m.name()), path, authorities));
                }
            }
        }
        return result;
    }

    /** 方法上的 @PreAuthorize 优先，否则取类上的；提取其中的权限码 */
    private static Set<String> authorities(HandlerMethod handler) {
        PreAuthorize pre = AnnotatedElementUtils.findMergedAnnotation(handler.getMethod(), PreAuthorize.class);
        if (pre == null) {
            pre = AnnotatedElementUtils.findMergedAnnotation(handler.getBeanType(), PreAuthorize.class);
        }
        Set<String> result = new HashSet<>();
        if (pre != null) {
            Matcher m = AUTHORITY.matcher(pre.value());
            while (m.find()) {
                result.add(m.group(1));
            }
        }
        return result;
    }

    private MvcResult call(Endpoint e, String token) throws Exception {
        MockHttpServletRequestBuilder req = MockMvcRequestBuilders.request(e.method(), e.path());
        if (token != null) {
            req.header("Authorization", token);
        }
        if (e.method() == HttpMethod.POST || e.method() == HttpMethod.PUT) {
            req.contentType(MediaType.APPLICATION_JSON).content("{}");
        }
        return mvc.perform(req).andReturn();
    }
}
