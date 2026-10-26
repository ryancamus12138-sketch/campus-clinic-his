-- =====================================================================
-- V23 名称与注释整理（不改变表结构和业务数据）
--
-- V18 去掉了双因素认证和审计链，但当时没有同步两处仍可见的文字：
--   1. 权限 105 的名称中还有“重置双因素”；
--   2. 操作日志表主键的注释还写着“审计链顺序”。
-- =====================================================================

UPDATE sys_permission SET perm_name = '重置口令/解锁', update_time = NOW() WHERE id = 105;

ALTER TABLE clinic_audit.audit_log
    MODIFY COLUMN id BIGINT NOT NULL AUTO_INCREMENT COMMENT '自增主键';
