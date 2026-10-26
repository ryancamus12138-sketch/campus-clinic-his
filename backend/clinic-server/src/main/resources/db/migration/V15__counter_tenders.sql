-- 窗口组合收费、多种收款方式、医保同步结算、医保移动支付
--   pay_order：一张收费单可由多笔支付单付清（如 校园卡 + 现金）；新增银行卡（POS）、医保移动支付渠道
--   refund_tender：一笔退费按原收款方式拆分原路退回
--   insurance_settlement：先落本地“结算中”记录再调医保，超时重试用同一业务号；区分窗口结算与移动支付

ALTER TABLE pay_order
    MODIFY COLUMN channel TINYINT NOT NULL COMMENT '1现金 2校园卡 3支付宝 4微信 5医保个人账户 6银行卡 7医保移动支付',
    ADD COLUMN tender_ref VARCHAR(64) NULL COMMENT '窗口收款凭据：校园卡号、POS 参考号、付款码渠道等' AFTER channel_trade_no;

CREATE TABLE refund_tender (
    id                BIGINT        NOT NULL PRIMARY KEY,
    refund_order_id   BIGINT        NOT NULL,
    pay_order_id      BIGINT        NOT NULL,
    channel           TINYINT       NOT NULL,
    amount            DECIMAL(12,2) NOT NULL,
    status            TINYINT       NOT NULL COMMENT '0待退 1成功 2失败',
    channel_refund_no VARCHAR(64)   NULL,
    create_time       DATETIME      NOT NULL,
    update_time       DATETIME      NOT NULL,
    create_by         BIGINT        NOT NULL DEFAULT 0,
    update_by         BIGINT        NOT NULL DEFAULT 0,
    version           INT           NOT NULL DEFAULT 0,
    CONSTRAINT ck_refund_tender_amount CHECK (amount > 0),
    UNIQUE KEY uk_refund_pay (refund_order_id, pay_order_id),
    KEY idx_pay (pay_order_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='退费按收款方式拆分';

ALTER TABLE insurance_settlement
    MODIFY COLUMN status TINYINT NOT NULL COMMENT '0结算中 1已结算 2已撤销',
    MODIFY COLUMN setl_id VARCHAR(64) NULL COMMENT '医保中心结算流水号，结算成功后回填',
    MODIFY COLUMN medfee DECIMAL(12,2) NULL,
    MODIFY COLUMN fund_pay DECIMAL(12,2) NULL,
    MODIFY COLUMN cash_pay DECIMAL(12,2) NULL,
    MODIFY COLUMN settle_time DATETIME NULL,
    ADD COLUMN source TINYINT NOT NULL DEFAULT 1 COMMENT '1窗口结算 2医保移动支付' AFTER status,
    ADD COLUMN cert_type VARCHAR(2) NULL COMMENT '就诊凭证类型：01医保电子凭证 02居民身份证 03社会保障卡' AFTER source;
