-- 患者不再登录本系统（见 V20）：删除演示患者的登录账号，保留演示患者档案（90001 学生、90002 教工）
DELETE FROM sys_user_role WHERE user_id IN (6, 7);
DELETE FROM sys_user WHERE id IN (6, 7) AND user_type = 2;
