-- =====================================================================
-- P2 收费与支付
-- =====================================================================

-- 患者类型快照：报表按“学生/教职工/其他”统计，以收费当时的身份为准
ALTER TABLE charge_order
    ADD COLUMN patient_type TINYINT NOT NULL DEFAULT 3 COMMENT '快照：1学生 2教职工 3其他' AFTER patient_id;

ALTER TABLE charge_order_item
    MODIFY COLUMN ref_type TINYINT NOT NULL COMMENT '来源：1挂号 2处方明细 3体检预约 4疫苗预约 5收费处手工划价';

-- 一张收费单同一时刻最多一笔待支付的支付单。
-- 患者连点“去支付”、或同时在两台设备上发起支付时，第二笔会撞唯一键，从而复用第一笔，
-- 避免同一笔费用被两个渠道各扣一次。
ALTER TABLE pay_order
    ADD COLUMN pending_charge_id BIGINT AS (IF(status = 0, charge_order_id, NULL)) VIRTUAL AFTER charge_order_id,
    ADD UNIQUE KEY uk_one_pending (pending_charge_id);
