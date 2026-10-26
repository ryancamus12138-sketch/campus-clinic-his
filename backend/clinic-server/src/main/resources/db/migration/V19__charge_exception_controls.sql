-- 收费异常的可追溯处理（只增加可空列、放宽注释，兼容已有数据）
--
-- 1. 收款异常（counter_checkout.status = 3）在财务处理之前阻止该收费单再收费、线上支付、作废；
--    这由应用在锁住收费单行后判断（见 ChargeOrderGuard），不改动原有的“只有一条进行中”唯一索引。
--    财务处理记录处理人、处理时间和说明。
ALTER TABLE counter_checkout
    MODIFY COLUMN status TINYINT NOT NULL COMMENT '0进行中 1已完成 2已撤回 3待财务处理（阻止再收费、作废） 4已处理',
    ADD COLUMN resolve_time DATETIME NULL COMMENT '财务处理时间' AFTER resolver_id;

-- 2. 医保结算：撤销失败时可由财务确认“已在医保端手工撤销”；平台未接入时由收费员在医保结算终端办理后登记
ALTER TABLE insurance_settlement
    MODIFY COLUMN source TINYINT NOT NULL DEFAULT 1 COMMENT '1平台结算（窗口） 2医保移动支付 3医保终端办理后人工登记',
    ADD COLUMN operator_id BIGINT       NULL COMMENT '人工登记结算结果的收费员' AFTER cert_type,
    ADD COLUMN cancel_by   BIGINT       NULL COMMENT '人工撤销登记人' AFTER cancel_time,
    ADD COLUMN cancel_note VARCHAR(200) NULL COMMENT '人工撤销说明' AFTER cancel_by;

-- 3. 现金、银行卡退款不再自动视为成功：转为“待收费员确认”，记录确认人、时间、班次与说明（银行卡为 POS 退货参考号）
ALTER TABLE refund_tender
    MODIFY COLUMN status TINYINT NOT NULL COMMENT '0待退 1已退 2失败 3待收费员确认',
    ADD COLUMN confirm_by       BIGINT       NULL COMMENT '确认已退还的收费员' AFTER channel_refund_no,
    ADD COLUMN confirm_time     DATETIME     NULL AFTER confirm_by,
    ADD COLUMN confirm_shift_id BIGINT       NULL COMMENT '确认人所在班次，现金从该班次钱箱付出' AFTER confirm_time,
    ADD COLUMN confirm_note     VARCHAR(200) NULL AFTER confirm_shift_id;

-- 已完成的历史退费保持原状；尚未完成、仍挂着的现金/银行卡待退记录转为待收费员确认
UPDATE refund_tender rt JOIN refund_order r ON r.id = rt.refund_order_id
SET rt.status = 3
WHERE rt.status = 0 AND rt.channel IN (1, 6) AND r.status = 1;

-- 4. 交班：长短款说明；财务复核时间与意见
ALTER TABLE cashier_shift
    ADD COLUMN diff_note   VARCHAR(200) NULL COMMENT '长短款说明（实点与应有不一致时必填）' AFTER counted_cash,
    ADD COLUMN review_time DATETIME     NULL AFTER reviewer_id,
    ADD COLUMN review_note VARCHAR(200) NULL AFTER review_time;

-- 5. 外部平台未接入时的人工登记：电子票据由财务在票据平台开具后登记票号与登记人
ALTER TABLE e_invoice
    MODIFY COLUMN status TINYINT NOT NULL COMMENT '0待开具（平台未接入时为待人工开具） 1已开 2失败',
    ADD COLUMN issue_mode TINYINT NOT NULL DEFAULT 1 COMMENT '1平台开具 2人工开具后登记' AFTER status,
    ADD COLUMN issued_by  BIGINT  NULL COMMENT '人工登记人' AFTER issue_mode;
