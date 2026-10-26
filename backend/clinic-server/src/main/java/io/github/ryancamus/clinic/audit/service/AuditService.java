package io.github.ryancamus.clinic.audit.service;

import io.github.ryancamus.clinic.audit.api.AuditApi;
import io.github.ryancamus.clinic.audit.domain.entity.AuditLog;
import io.github.ryancamus.clinic.audit.mapper.AuditLogMapper;
import io.github.ryancamus.clinic.common.result.TraceContext;
import io.github.ryancamus.clinic.framework.security.LoginUser;
import io.github.ryancamus.clinic.framework.security.SecurityAuditEvent;
import io.github.ryancamus.clinic.framework.security.SecurityUtils;
import io.github.ryancamus.clinic.framework.time.WallClock;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.temporal.ChronoUnit;

/**
 * 审计日志写入。
 * <p>
 * 每条日志是一条独立的自动提交 INSERT，不参与业务事务：业务回滚时，“谁在什么时候尝试做了什么、失败了”同样要留下。
 * 写入失败不影响业务结果，但会把整条事件以 ERROR 级别写进应用日志。
 * 日志存放在独立库 clinic_audit，应用账号对它只有查询和插入权限：业务程序能写日志，但改不了、删不了。
 */
@Slf4j
@Service
public class AuditService implements AuditApi {

    private final AuditLogMapper mapper;
    /** 审计时间用真实时钟，不用测试中可调的业务时钟 */
    private final WallClock wallClock;

    public AuditService(AuditLogMapper mapper, WallClock wallClock) {
        this.mapper = mapper;
        this.wallClock = wallClock;
    }

    @Override
    public void record(String action, String actionName, String target, boolean success, String detail) {
        AuditLog entry = newEntry(action, actionName, success, detail);
        entry.setTarget(truncate(target, 200));
        SecurityUtils.currentUser().ifPresent(u -> fillUser(entry, u));
        write(entry);
    }

    @Override
    public void recordAs(Long userId, String username, String action, String actionName, boolean success,
                         String detail) {
        AuditLog entry = newEntry(action, actionName, success, detail);
        entry.setUserId(userId);
        entry.setUsername(truncate(username, 50));
        write(entry);
    }

    /** 拦截器记录接口调用（不在 AuditApi 中：只有本模块的拦截器使用） */
    public void recordRequest(HttpServletRequest request, String action, String actionName, String target, int status,
                       Integer errorCode) {
        AuditLog entry = newEntry(action, actionName, status < 400, null);
        entry.setTarget(truncate(target, 200));
        entry.setHttpStatus(status);
        entry.setErrorCode(errorCode);
        SecurityUtils.currentUser().ifPresent(u -> fillUser(entry, u));
        write(entry);
    }

    @EventListener
    public void onSecurityEvent(SecurityAuditEvent event) {
        record(event.action(), event.actionName(), event.target(), event.success(), null);
    }

    private AuditLog newEntry(String action, String actionName, boolean success, String detail) {
        AuditLog entry = new AuditLog();
        // 截到毫秒：与 DATETIME(3) 一致
        entry.setEventTime(wallClock.now().truncatedTo(ChronoUnit.MILLIS));
        entry.setAction(truncate(action, 64));
        entry.setActionName(truncate(actionName, 100));
        entry.setSuccess(success ? 1 : 0);
        entry.setDetail(truncate(detail, 1000));
        entry.setTraceId(TraceContext.get());
        ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attributes != null) {
            HttpServletRequest request = attributes.getRequest();
            // 经 Nginx 转发时，Tomcat 的 RemoteIpValve 已按 X-Forwarded-For 还原出真实客户端地址
            entry.setClientIp(truncate(request.getRemoteAddr(), 45));
            entry.setUserAgent(truncate(request.getHeader("User-Agent"), 200));
            entry.setHttpMethod(request.getMethod());
            entry.setUri(truncate(request.getRequestURI(), 200));
        }
        return entry;
    }

    private static void fillUser(AuditLog entry, LoginUser user) {
        entry.setUserId(user.getUserId());
        entry.setUsername(user.getUsername());
    }

    private void write(AuditLog entry) {
        try {
            mapper.insert(entry);
        } catch (RuntimeException e) {
            log.error("AUDIT-WRITE-FAILED action={} user={} target={} success={} trace={}", entry.getAction(),
                    entry.getUsername(), entry.getTarget(), entry.getSuccess(), entry.getTraceId(), e);
        }
    }

    private static String truncate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }
}
