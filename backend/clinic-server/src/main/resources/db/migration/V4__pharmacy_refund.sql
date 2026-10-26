-- =====================================================================
-- P3 药房库存与退费
-- =====================================================================

-- 一条锁定记录 = 某条收费明细在某个批次上锁定的数量。按数量记录它的去向：
--   锁定中 = qty - dispensed_qty - released_qty（批次 qty_locked 的组成部分）
--   已发出 = dispensed_qty - returned_qty
-- 部分发药、部分退药、部分退费释放都只是改这几个数量，CHECK 保证不会多发、多退、多释放。
ALTER TABLE stock_lock
    ADD COLUMN dispensed_qty INT NOT NULL DEFAULT 0 COMMENT '累计发药' AFTER qty,
    ADD COLUMN released_qty  INT NOT NULL DEFAULT 0 COMMENT '累计释放（关单、退费）' AFTER dispensed_qty,
    ADD COLUMN returned_qty  INT NOT NULL DEFAULT 0 COMMENT '累计退药' AFTER released_qty,
    MODIFY COLUMN status TINYINT NOT NULL COMMENT '1锁定中 2已发药 3已释放 4已全部退药（由数量推导，便于查询）',
    ADD CONSTRAINT ck_lock_progress CHECK (dispensed_qty >= 0 AND released_qty >= 0 AND returned_qty >= 0
        AND dispensed_qty + released_qty <= qty AND returned_qty <= dispensed_qty);

-- 退费：amount 为退回患者的现金（个人自付部分），insurance_amount 为医保统筹冲销，不产生现金流。
-- 同一收费单同时只允许一笔未完结的退费（待审核、退款中、失败待重试），
-- 退费金额按“累计已退件数”计算，串行化后每一步都基于确定的前序状态，不会出现分毫偏差。
ALTER TABLE refund_order
    ADD COLUMN insurance_amount DECIMAL(12,2) NOT NULL DEFAULT 0 COMMENT '医保统筹冲销' AFTER amount,
    ADD COLUMN success_time     DATETIME      NULL AFTER channel_refund_no,
    ADD COLUMN retry_count      INT           NOT NULL DEFAULT 0 AFTER success_time,
    ADD COLUMN active_charge_id BIGINT AS (IF(status IN (0, 1, 3), charge_order_id, NULL)) VIRTUAL,
    ADD UNIQUE KEY uk_one_active_refund (active_charge_id);

ALTER TABLE refund_item
    ADD COLUMN insurance_amount DECIMAL(12,2) NOT NULL DEFAULT 0 AFTER amount,
    ADD COLUMN self_amount      DECIMAL(12,2) NOT NULL DEFAULT 0 AFTER insurance_amount,
    ADD CONSTRAINT ck_refund_item_split CHECK (amount = insurance_amount + self_amount);

-- 处方生成的收费单（医生开方即划价）
ALTER TABLE prescription
    ADD COLUMN charge_order_id BIGINT NULL COMMENT '开方时生成的收费单' AFTER doctor_id;
