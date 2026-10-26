-- =====================================================================
-- 基础参考数据：院区、科室、角色、权限。所有环境都需要。
-- 参考数据使用固定的小整数 ID，与业务数据的雪花 ID 不会冲突。
-- =====================================================================

INSERT INTO sys_campus (id, name, address, create_time, update_time)
VALUES (1, '主校区', '（示例地址，部署时修改）', NOW(), NOW()),
       (2, '东校区', '（示例地址，部署时修改）', NOW(), NOW());

INSERT INTO sys_dept (id, campus_id, dept_code, dept_name, dept_type, sort, create_time, update_time)
VALUES (101, 1, 'CA-NK', '内科', 1, 1, NOW(), NOW()),
       (102, 1, 'CA-WK', '外科', 1, 2, NOW(), NOW()),
       (103, 1, 'CA-KQ', '口腔科', 1, 3, NOW(), NOW()),
       (104, 1, 'CA-TJ', '体检中心', 2, 4, NOW(), NOW()),
       (105, 1, 'CA-YM', '预防接种门诊', 1, 5, NOW(), NOW()),
       (106, 1, 'CA-YF', '药房', 3, 6, NOW(), NOW()),
       (107, 1, 'CA-SF', '收费处', 4, 7, NOW(), NOW()),
       (201, 2, 'CB-NK', '内科', 1, 1, NOW(), NOW()),
       (206, 2, 'CB-YF', '药房', 3, 6, NOW(), NOW()),
       (207, 2, 'CB-SF', '收费处', 4, 7, NOW(), NOW());

INSERT INTO sys_role (id, role_code, role_name, data_scope, create_time, update_time)
VALUES (1, 'ADMIN', '系统管理员', 1, NOW(), NOW()),
       (2, 'CASHIER', '收费员', 2, NOW(), NOW()),
       (3, 'PHARMACIST', '药师', 2, NOW(), NOW()),
       (4, 'DOCTOR', '医生', 2, NOW(), NOW()),
       (5, 'FINANCE', '财务', 1, NOW(), NOW()),
       (6, 'PATIENT', '患者', 3, NOW(), NOW());

-- perm_type: 1 菜单 2 按钮/接口
INSERT INTO sys_permission (id, parent_id, perm_code, perm_name, perm_type, path, sort, create_time, update_time)
VALUES (100, 0, 'system', '系统管理', 1, '/system', 90, NOW(), NOW()),
       (101, 100, 'system:user:list', '用户管理', 1, '/system/users', 1, NOW(), NOW()),
       (102, 101, 'system:user:create', '新增用户', 2, NULL, 1, NOW(), NOW()),
       (103, 101, 'system:user:update', '修改用户', 2, NULL, 2, NOW(), NOW()),
       (104, 100, 'system:role:list', '角色管理', 1, '/system/roles', 2, NOW(), NOW()),
       (200, 0, 'schedule', '号源管理', 1, '/schedule', 10, NOW(), NOW()),
       (201, 200, 'schedule:slot:list', '号源查询', 1, '/schedule/slots', 1, NOW(), NOW()),
       (202, 201, 'schedule:slot:manage', '排班放号', 2, NULL, 1, NOW(), NOW()),
       (300, 0, 'charge', '门诊收费', 1, '/charge', 20, NOW(), NOW()),
       (301, 300, 'charge:order:list', '收费单查询', 1, '/charge/orders', 1, NOW(), NOW()),
       (302, 300, 'charge:settle', '收费结算', 1, '/charge/settle', 2, NOW(), NOW()),
       (303, 300, 'charge:refund:apply', '退费申请', 2, NULL, 3, NOW(), NOW()),
       (304, 300, 'charge:refund:approve', '退费审批', 1, '/charge/refunds', 4, NOW(), NOW()),
       (400, 0, 'pharmacy', '药房管理', 1, '/pharmacy', 30, NOW(), NOW()),
       (401, 400, 'pharmacy:dispense', '发药退药', 1, '/pharmacy/dispense', 1, NOW(), NOW()),
       (402, 400, 'pharmacy:stock:list', '库存查询', 1, '/pharmacy/stock', 2, NOW(), NOW()),
       (403, 402, 'pharmacy:stock:manage', '入库盘点', 2, NULL, 1, NOW(), NOW()),
       (500, 0, 'finance', '财务管理', 1, '/finance', 40, NOW(), NOW()),
       (501, 500, 'finance:shift', '日结交班', 1, '/finance/shifts', 1, NOW(), NOW()),
       (502, 500, 'finance:report', '统计报表', 1, '/finance/reports', 2, NOW(), NOW()),
       (503, 500, 'finance:recon', '渠道对账', 1, '/finance/recon', 3, NOW(), NOW()),
       (600, 0, 'patient', '患者服务', 1, NULL, 50, NOW(), NOW()),
       (601, 600, 'patient:self', '本人就诊与账单', 2, NULL, 1, NOW(), NOW()),
       (700, 0, 'clinic', '门诊诊疗', 1, '/clinic', 15, NOW(), NOW()),
       (701, 700, 'clinic:prescribe', '开具处方', 1, '/clinic/prescribe', 1, NOW(), NOW());

-- 管理员拥有全部权限
INSERT INTO sys_role_permission (role_id, permission_id)
SELECT 1, id FROM sys_permission;

INSERT INTO sys_role_permission (role_id, permission_id)
SELECT 2, id FROM sys_permission
WHERE perm_code IN ('charge', 'charge:order:list', 'charge:settle', 'charge:refund:apply',
                    'schedule', 'schedule:slot:list', 'finance', 'finance:shift');

INSERT INTO sys_role_permission (role_id, permission_id)
SELECT 3, id FROM sys_permission
WHERE perm_code IN ('pharmacy', 'pharmacy:dispense', 'pharmacy:stock:list', 'pharmacy:stock:manage');

INSERT INTO sys_role_permission (role_id, permission_id)
SELECT 4, id FROM sys_permission
WHERE perm_code IN ('clinic', 'clinic:prescribe', 'schedule', 'schedule:slot:list');

INSERT INTO sys_role_permission (role_id, permission_id)
SELECT 5, id FROM sys_permission
WHERE perm_code IN ('finance', 'finance:shift', 'finance:report', 'finance:recon',
                    'charge', 'charge:order:list', 'charge:refund:approve');

INSERT INTO sys_role_permission (role_id, permission_id)
SELECT 6, id FROM sys_permission
WHERE perm_code IN ('patient', 'patient:self');
