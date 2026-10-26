-- =====================================================================
-- V21 收款异常逐笔核实、退费审批留痕、POS 结算单核对、岗位分离
--
-- 1. 收款异常：撤不回或结果不明的每一笔收款，财务要逐笔核实“已退还患者”或“确认未扣款”，
--    记录核实人、时间、凭证号（POS 撤销/退货参考号、平台流水号）和说明；全部核实后才能解除锁定。
--    原先只填一段处理说明就能解除，收费单随即可再次收费，若有一笔实际已扣款，患者会被重复收费。
-- 2. 退费审批：记录审批时间和审批意见；驳回必须写明理由（原先只记审批人）。
-- 3. 交班：银行卡（POS）收款与 POS 机结算单核对，记录系统应有金额、结算单金额（原先只核对现金）。
-- 4. 收款异常处理使用独立权限，不再借用“退费审批”。
-- 不修改历史迁移；已有数据的新列为空。
-- =====================================================================

ALTER TABLE counter_checkout_tender
    MODIFY COLUMN status TINYINT NOT NULL COMMENT '0收取中 1已收 2已撤回 3撤回失败 4已核实退还 5已核实未扣款',
    ADD COLUMN verify_by   BIGINT       NULL COMMENT '核实人（财务）' AFTER tender_ref,
    ADD COLUMN verify_time DATETIME     NULL AFTER verify_by,
    ADD COLUMN verify_ref  VARCHAR(64)  NULL COMMENT 'POS 撤销/退货参考号、平台流水号等凭证' AFTER verify_time,
    ADD COLUMN verify_note VARCHAR(200) NULL AFTER verify_ref;

ALTER TABLE refund_order
    ADD COLUMN approve_time DATETIME     NULL COMMENT '审批（通过或驳回）时间' AFTER approver_id,
    ADD COLUMN approve_note VARCHAR(200) NULL COMMENT '审批意见；驳回时必填' AFTER approve_time;

ALTER TABLE cashier_shift
    ADD COLUMN pos_expected DECIMAL(12,2) NULL COMMENT '本班银行卡（POS）净额：收款 − 退款，由流水汇总' AFTER counted_cash,
    ADD COLUMN pos_counted  DECIMAL(12,2) NULL COMMENT '收费员录入的 POS 机结算单金额' AFTER pos_expected;

-- 收款异常处理：财务岗位
INSERT INTO sys_permission (id, parent_id, perm_code, perm_name, perm_type, path, sort, create_time, update_time)
VALUES (305, 300, 'charge:exception:handle', '收款异常处理', 1, '/charge/checkout-exceptions', 5, NOW(), NOW());
INSERT INTO sys_role_permission (role_id, permission_id)
SELECT r.id, 305 FROM sys_role r WHERE r.role_code IN ('ADMIN', 'FINANCE');
