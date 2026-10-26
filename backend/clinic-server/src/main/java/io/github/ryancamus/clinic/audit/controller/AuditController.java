package io.github.ryancamus.clinic.audit.controller;

import io.github.ryancamus.clinic.audit.api.AuditApi;
import io.github.ryancamus.clinic.audit.domain.vo.AuditLogVO;
import io.github.ryancamus.clinic.audit.service.AuditQueryService;
import io.github.ryancamus.clinic.common.result.PageResult;
import io.github.ryancamus.clinic.common.result.R;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/**
 * 审计日志查询，仅审计管理员可用。系统管理员没有这些权限（三权分立：管理与审计分离），
 * 审计员的查询本身也会被记录。
 */
@Tag(name = "安全审计")
@Validated
@RestController
@RequestMapping("/api/audit")
@RequiredArgsConstructor
public class AuditController {

    private final AuditQueryService queryService;
    private final AuditApi auditApi;

    @Operation(summary = "审计日志查询")
    @GetMapping("/logs")
    @PreAuthorize("hasAuthority('audit:log:view')")
    public R<PageResult<AuditLogVO>> logs(@RequestParam(required = false) String username,
                                         @RequestParam(required = false) String action,
                                         @RequestParam(required = false) Boolean success,
                                         @RequestParam(required = false)
                                         @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                         @RequestParam(required = false)
                                         @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                         @RequestParam(defaultValue = "1") @Min(1) long page,
                                         @RequestParam(defaultValue = "20") @Min(1) @Max(200) long size) {
        auditApi.record("AUDIT_QUERY", "查询审计日志", null, true,
                "username=" + username + ",action=" + action + ",from=" + from + ",to=" + to);
        return R.ok(queryService.page(username, action, success, from, to, page, size));
    }
}
