-- 面向国内高校校医院的通用化（只改数据与新增表，兼容已有数据）
--
-- 1. 参考数据中的两个示例校区改为中性的名称与地址（此前没有维护校区的界面，这两行只可能是初始参考数据），
--    部署时由管理员按本校实际修改。科室编码前缀同步改为与校区无关的 A- / B-。
UPDATE sys_campus SET name = '主校区', address = '（示例地址，部署时修改）' WHERE id = 1;
UPDATE sys_campus SET name = '东校区', address = '（示例地址，部署时修改）' WHERE id = 2;
UPDATE sys_dept SET dept_code = CONCAT('A-', SUBSTRING(dept_code, 4)) WHERE id BETWEEN 101 AND 107;
UPDATE sys_dept SET dept_code = CONCAT('B-', SUBSTRING(dept_code, 4)) WHERE id BETWEEN 201 AND 207;

-- 2. 患者不再登录本系统：学生、教职工的线上预约与缴费由学校统一支付平台、医保移动支付等现有平台提供，
--    不属于收费管理系统。删除患者角色、权限和患者账号；患者档案（patient 表）不受影响。
DELETE FROM sys_user_role WHERE role_id IN (SELECT id FROM sys_role WHERE role_code = 'PATIENT')
                             OR user_id IN (SELECT id FROM sys_user WHERE user_type = 2);
DELETE FROM sys_user WHERE user_type = 2;
DELETE FROM sys_role_permission WHERE role_id IN (SELECT id FROM sys_role WHERE role_code = 'PATIENT')
                                   OR permission_id IN (SELECT id FROM sys_permission WHERE perm_code = 'patient:self');
DELETE FROM sys_permission WHERE perm_code = 'patient:self';
UPDATE sys_permission SET perm_name = '患者档案' WHERE perm_code = 'patient';
DELETE FROM sys_role WHERE role_code = 'PATIENT';
ALTER TABLE sys_user
    MODIFY COLUMN user_type  TINYINT NOT NULL COMMENT '1员工（患者账号已停用）',
    MODIFY COLUMN patient_id BIGINT  NULL COMMENT '已停用：原患者账号关联的患者档案';

-- 3. 收费点：每个校区的收费窗口（可以有多个），以及该收费点开放的窗口收款方式。
--    收款方式还要看对应外部平台是否已接入（如校园卡需要接入一卡通），两者同时满足才在收费界面出现。
--    收费员的班次记录所在收费点，交班、对账按收费点区分。
CREATE TABLE charge_point (
    id             BIGINT       NOT NULL PRIMARY KEY,
    campus_id      BIGINT       NOT NULL,
    code           VARCHAR(32)  NOT NULL,
    name           VARCHAR(50)  NOT NULL,
    counter_methods VARCHAR(100) NOT NULL COMMENT '开放的窗口收款方式，逗号分隔：CASH,BANK_CARD,CAMPUS_CARD,AUTH_CODE',
    status         TINYINT      NOT NULL DEFAULT 1 COMMENT '1启用 0停用',
    sort           INT          NOT NULL DEFAULT 0,
    create_time    DATETIME     NOT NULL,
    update_time    DATETIME     NOT NULL,
    create_by      BIGINT       NOT NULL DEFAULT 0,
    update_by      BIGINT       NOT NULL DEFAULT 0,
    version        INT          NOT NULL DEFAULT 0,
    UNIQUE KEY uk_code (code),
    KEY idx_campus (campus_id, status, sort)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='收费点（收费窗口）';

INSERT INTO charge_point (id, campus_id, code, name, counter_methods, status, sort, create_time, update_time)
VALUES (1, 1, 'A-SF', '主校区收费处', 'CASH,BANK_CARD,CAMPUS_CARD,AUTH_CODE', 1, 1, NOW(), NOW()),
       (2, 2, 'B-SF', '东校区收费处', 'CASH,BANK_CARD,CAMPUS_CARD,AUTH_CODE', 1, 1, NOW(), NOW());

ALTER TABLE cashier_shift
    ADD COLUMN charge_point_id BIGINT NULL COMMENT '收费点；历史班次为空' AFTER campus_id;

-- 管理员维护校区收费点
INSERT INTO sys_permission (id, parent_id, perm_code, perm_name, perm_type, path, sort, create_time, update_time)
VALUES (110, 100, 'system:charge-point', '收费点', 1, '/system/charge-points', 3, NOW(), NOW());
INSERT INTO sys_role_permission (role_id, permission_id)
SELECT r.id, 110 FROM sys_role r WHERE r.role_code = 'ADMIN';
