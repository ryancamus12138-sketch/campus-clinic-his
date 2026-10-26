package io.github.ryancamus.clinic.audit.web;

import io.github.ryancamus.clinic.framework.web.GlobalExceptionHandler;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.lang.NonNull;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * 统一记录接口调用：所有写操作（POST/PUT/PATCH/DELETE），以及任何被拒绝（403）的请求。
 * <p>
 * 由拦截器统一记录，新增接口不会因为忘了加注解而漏记。记录的是“谁、何时、从哪、调用了什么、对象是谁、结果如何”，
 * 不记录请求体：请求体里可能有证件号等敏感信息，审计日志本身不应成为新的泄露点。
 * 登录、退出由认证服务显式记录（那时还没有登录态或即将失去登录态）。
 */
public class AuditInterceptor implements HandlerInterceptor {

    private static final Set<String> WRITE_METHODS = Set.of("POST", "PUT", "PATCH", "DELETE");

    private final AuditRecorder recorder;

    public AuditInterceptor(AuditRecorder recorder) {
        this.recorder = recorder;
    }

    @Override
    public void afterCompletion(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response,
                                @NonNull Object handler, Exception ex) {
        if (!(handler instanceof HandlerMethod method)) {
            return;
        }
        int status = response.getStatus();
        if (!WRITE_METHODS.contains(request.getMethod()) && status != HttpServletResponse.SC_FORBIDDEN) {
            return;
        }
        String controller = method.getBeanType().getSimpleName().replace("Controller", "");
        String action = controller + "." + method.getMethod().getName();
        Operation operation = method.getMethodAnnotation(Operation.class);
        String actionName = operation == null ? null : operation.summary();
        Object errorCode = request.getAttribute(GlobalExceptionHandler.ERROR_CODE_ATTRIBUTE);
        recorder.record(request, action, actionName, target(request), status,
                errorCode instanceof Integer code ? code : null);
    }

    @SuppressWarnings("unchecked")
    private static String target(HttpServletRequest request) {
        Object vars = request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        if (!(vars instanceof Map<?, ?> map) || map.isEmpty()) {
            return null;
        }
        return new TreeMap<>((Map<String, String>) map).entrySet().stream()
                .map(e -> e.getKey() + "=" + e.getValue())
                .collect(Collectors.joining(","));
    }

    /** 拦截器与服务之间的窄接口，便于测试 */
    @FunctionalInterface
    public interface AuditRecorder {
        void record(HttpServletRequest request, String action, String actionName, String target, int status,
                    Integer errorCode);
    }
}
