-- =====================================================================
-- V7 账号安全：口令有效期与强制修改、双因素认证、按角色要求双因素、账号管理权限
-- =====================================================================

ALTER TABLE sys_user
    ADD COLUMN password_changed_at  DATETIME       NULL COMMENT '口令最近修改时间，为空按创建时间算有效期' AFTER password_hash,
    ADD COLUMN must_change_password TINYINT        NOT NULL DEFAULT 0 COMMENT '1 下次登录必须修改口令' AFTER password_changed_at,
    ADD COLUMN totp_secret_enc      VARBINARY(128) NULL COMMENT '动态口令密钥密文（SM4-GCM）' AFTER must_change_password,
    ADD COLUMN totp_enabled         TINYINT        NOT NULL DEFAULT 0 COMMENT '1 已绑定身份验证器' AFTER totp_secret_enc;

-- 按角色要求双因素：生产环境另有全局开关（所有员工），这里用于在全局开关打开前先对高权限角色启用
ALTER TABLE sys_role
    ADD COLUMN require_mfa TINYINT NOT NULL DEFAULT 0 COMMENT '1 该角色的账号必须使用动态口令' AFTER data_scope;

INSERT INTO sys_permission (id, parent_id, perm_code, perm_name, perm_type, path, sort, create_time, update_time)
VALUES (105, 101, 'system:user:reset', '重置口令/解锁/重置双因素', 2, NULL, 3, NOW(), NOW());

INSERT INTO sys_role_permission (role_id, permission_id) VALUES (1, 105);
