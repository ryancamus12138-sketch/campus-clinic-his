package io.github.ryancamus.clinic.audit.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 审计日志。存放在独立库 clinic_audit，应用账号对该库只有 SELECT、INSERT 权限，不能修改或删除。
 * 主键自增。
 */
@Getter
@Setter
@TableName(schema = "clinic_audit", value = "audit_log")
public class AuditLog {

    @TableId(type = IdType.AUTO)
    private Long id;
    /** 真实发生时间（系统时钟，不受业务时钟影响） */
    private LocalDateTime eventTime;
    private Long userId;
    private String username;
    private String clientIp;
    private String userAgent;
    private String action;
    private String actionName;
    private String httpMethod;
    private String uri;
    private String target;
    /** 1 成功 0 失败 */
    private Integer success;
    private Integer httpStatus;
    private Integer errorCode;
    private String detail;
    private String traceId;
}
