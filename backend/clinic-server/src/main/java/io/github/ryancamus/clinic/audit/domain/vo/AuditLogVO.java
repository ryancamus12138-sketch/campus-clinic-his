package io.github.ryancamus.clinic.audit.domain.vo;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

@Schema(description = "审计日志")
public record AuditLogVO(
        Long id,
        LocalDateTime eventTime,
        Long userId,
        String username,
        String clientIp,
        String userAgent,
        String action,
        String actionName,
        String httpMethod,
        String uri,
        String target,
        boolean success,
        Integer httpStatus,
        Integer errorCode,
        String detail,
        String traceId) {
}
