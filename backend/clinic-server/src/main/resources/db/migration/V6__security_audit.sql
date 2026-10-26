-- =====================================================================
-- V6 安全合规：患者证件类型、敏感字段加密说明、审计日志（独立库）、审计员角色
-- =====================================================================

-- 患者证件：留学生持护照，港澳台学生持居住证或通行证
ALTER TABLE patient
    ADD COLUMN id_type TINYINT NOT NULL DEFAULT 1
        COMMENT '证件类型：1 居民身份证 2 护照 3 港澳台居民居住证 4 港澳台通行证 5 外国人永久居留身份证' AFTER insurance_type,
    MODIFY COLUMN id_card_enc VARBINARY(128) NOT NULL COMMENT '证件号密文：SM4-GCM，含密钥编号',
    MODIFY COLUMN id_card_hash CHAR(64) NOT NULL COMMENT '证件号盲索引：HMAC-SM3（带密钥，不可穷举）',
    MODIFY COLUMN phone_enc VARBINARY(64) NOT NULL COMMENT '手机号密文：SM4-GCM',
    MODIFY COLUMN phone_hash CHAR(64) NOT NULL COMMENT '手机号盲索引：HMAC-SM3';

-- ---------------------------------------------------------------------
-- 审计日志放在独立库 clinic_audit（由部署脚本以 root 创建）。
-- 应用账号 clinic_app 对该库只有 SELECT、INSERT 权限：不能改、不能删。
-- 留存：《网络安全法》第二十一条要求网络日志留存不少于六个月；本系统默认永久保留，
-- 归档由数据库管理员导出后再按月清理，清理会在审计链中留下可检测的断点。
-- ---------------------------------------------------------------------
CREATE TABLE clinic_audit.audit_log (
    id          BIGINT        NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '自增，顺序即审计链顺序',
    event_time  DATETIME(3)   NOT NULL COMMENT '发生时间（系统时钟）',
    user_id     BIGINT        NULL,
    username    VARCHAR(50)   NULL COMMENT '登录失败时为尝试的用户名',
    client_ip   VARCHAR(45)   NULL,
    user_agent  VARCHAR(200)  NULL,
    action      VARCHAR(64)   NOT NULL COMMENT '操作编码，如 LOGIN、Refund.approve',
    action_name VARCHAR(100)  NULL,
    http_method VARCHAR(8)    NULL,
    uri         VARCHAR(200)  NULL,
    target      VARCHAR(200)  NULL COMMENT '操作对象，如 id=123',
    success     TINYINT       NOT NULL,
    http_status INT           NULL,
    error_code  INT           NULL,
    detail      VARCHAR(1000) NULL COMMENT '补充说明，不含证件号等敏感值',
    trace_id    VARCHAR(64)   NULL,
    row_mac     CHAR(64)      NOT NULL COMMENT '行 MAC：HMAC-SM3(密钥, 全部内容)，由应用写入时计算',
    KEY idx_time (event_time),
    KEY idx_user (username, event_time),
    KEY idx_action (action, event_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='审计日志（只增不改）';

CREATE TABLE clinic_audit.audit_seal (
    log_id    BIGINT      NOT NULL PRIMARY KEY,
    prev_mac  CHAR(64)    NOT NULL,
    mac       CHAR(64)    NOT NULL COMMENT 'HMAC-SM3(密钥, prev_mac + 日志内容)',
    sealed_at DATETIME(3) NOT NULL
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='审计链封印';

-- ---------------------------------------------------------------------
-- 权限：患者建档、查看完整敏感信息、安全审计
-- 三权分立：系统管理员管账号与权限，审计管理员只看审计日志，两者互不包含
-- ---------------------------------------------------------------------
INSERT INTO sys_role (id, role_code, role_name, data_scope, create_time, update_time)
VALUES (7, 'AUDITOR', '审计管理员', 1, NOW(), NOW());

INSERT INTO sys_permission (id, parent_id, perm_code, perm_name, perm_type, path, sort, create_time, update_time)
VALUES (610, 600, 'patient:create', '患者建档', 1, '/patient/archive', 2, NOW(), NOW()),
       (611, 610, 'patient:sensitive:view', '查看完整证件号与手机号', 2, NULL, 1, NOW(), NOW()),
       (800, 0, 'audit', '安全审计', 1, '/audit', 95, NOW(), NOW()),
       (801, 800, 'audit:log:view', '审计日志', 1, '/audit/logs', 1, NOW(), NOW());

INSERT INTO sys_role_permission (role_id, permission_id)
VALUES (1, 610),
       (2, 600), (2, 610), (2, 611),
       (7, 800), (7, 801);
