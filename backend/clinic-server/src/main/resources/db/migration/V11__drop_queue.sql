-- 签到叫号与收费无直接关系，按项目主题（校医院收费系统）移除。V10 已发布，用新迁移回收表和权限
DELETE FROM sys_role_permission WHERE permission_id IN (710, 711);
DELETE FROM sys_role_permission WHERE role_id = 2 AND permission_id = 700;
DELETE FROM sys_permission WHERE id IN (710, 711);
DROP TABLE queue_ticket;
