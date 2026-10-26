-- 收敛安全设计，只保留能讲清、收费系统必需的部分：
--   去掉员工动态口令（双因素认证）与口令定期到期：首次登录修改临时口令保留
--   审计日志改为普通操作日志：去掉行 MAC 与链式封印（独立库、应用账号只能查询和插入保持不变）
ALTER TABLE sys_user
    DROP COLUMN totp_secret_enc,
    DROP COLUMN totp_enabled;

ALTER TABLE sys_role
    DROP COLUMN require_mfa;

DROP TABLE clinic_audit.audit_seal;

ALTER TABLE clinic_audit.audit_log
    DROP COLUMN row_mac;
