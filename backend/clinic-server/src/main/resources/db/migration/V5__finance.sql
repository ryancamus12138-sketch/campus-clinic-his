-- =====================================================================
-- P4 财务：交班、报表、对账、电子票据
-- =====================================================================

-- 资金流水同时记录医保统筹记账额（不是现金，但报表要按“总额 = 统筹 + 自付”出数）
ALTER TABLE fund_ledger
    ADD COLUMN insurance_amount DECIMAL(12,2) NOT NULL DEFAULT 0 COMMENT '医保统筹记账（退费为负）' AFTER amount;

-- 报表每日校准时记录重算时间
ALTER TABLE report_daily_agg
    ADD COLUMN update_time DATETIME NULL COMMENT '最近一次重算时间';

ALTER TABLE recon_diff
    ADD COLUMN remark VARCHAR(200) NULL COMMENT '处理说明' AFTER status;

ALTER TABLE e_invoice
    ADD COLUMN patient_id       BIGINT        NULL AFTER charge_order_id,
    ADD COLUMN charge_no        VARCHAR(32)   NULL AFTER patient_id,
    ADD COLUMN self_amount      DECIMAL(12,2) NOT NULL DEFAULT 0 AFTER amount,
    ADD COLUMN insurance_amount DECIMAL(12,2) NOT NULL DEFAULT 0 AFTER self_amount,
    ADD COLUMN pdf_url          VARCHAR(255)  NULL AFTER insurance_amount,
    ADD COLUMN issue_time       DATETIME      NULL AFTER pdf_url,
    ADD COLUMN retry_count      INT           NOT NULL DEFAULT 0 AFTER issue_time,
    ADD KEY idx_patient (patient_id, create_time);

-- 交班单的现金应收（现金收 − 现金退），交班时写入
ALTER TABLE cashier_shift
    ADD COLUMN expected_cash DECIMAL(12,2) NULL COMMENT '现金应有 = 现金收款 − 现金退款' AFTER counted_cash,
    ADD COLUMN reviewer_id   BIGINT        NULL AFTER expected_cash;

-- 财务菜单：电子票据
INSERT INTO sys_permission (id, parent_id, perm_code, perm_name, perm_type, path, sort, create_time, update_time)
VALUES (504, 500, 'finance:invoice', '电子票据', 1, '/finance/invoices', 4, NOW(), NOW());
INSERT INTO sys_role_permission (role_id, permission_id) VALUES (1, 504), (5, 504);

-- 退费在哪个班次办理：现金退款从该收费员的钱箱付出
ALTER TABLE refund_order
    ADD COLUMN shift_id BIGINT NULL AFTER approver_id;

-- 发件箱：记录最近一次失败原因，便于排查死信
ALTER TABLE outbox_message
    ADD COLUMN last_error VARCHAR(500) NULL AFTER next_retry_time;

-- 同一聚合的事件按序消费：投递时检查同一聚合是否有更早的未投递事件
ALTER TABLE outbox_message
    ADD KEY idx_aggregate (aggregate_id, status, id);
