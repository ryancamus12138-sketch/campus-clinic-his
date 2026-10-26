-- 医保结算记录。统筹与自付的分摊以医保结算结果为准，本表记录每次结算与撤销，
-- 供退费撤销重结、日终与医保中心对账使用。
CREATE TABLE insurance_settlement (
    id                BIGINT        NOT NULL PRIMARY KEY,
    charge_order_id   BIGINT        NOT NULL,
    biz_no            VARCHAR(32)   NOT NULL COMMENT '结算业务号：首次为收费单号，退费重结为退费单号',
    setl_id           VARCHAR(64)   NOT NULL COMMENT '医保中心结算流水号',
    status            TINYINT       NOT NULL COMMENT '1已结算 2已撤销',
    medfee            DECIMAL(12,2) NOT NULL COMMENT '医疗费总额',
    fund_pay          DECIMAL(12,2) NOT NULL COMMENT '基金支付',
    cash_pay          DECIMAL(12,2) NOT NULL COMMENT '个人现金支付',
    expected_fund_pay DECIMAL(12,2) NOT NULL COMMENT '本系统预结算的基金支付',
    settle_time       DATETIME      NOT NULL,
    cancel_time       DATETIME      NULL,
    create_time       DATETIME      NOT NULL,
    update_time       DATETIME      NOT NULL,
    create_by         BIGINT        NOT NULL DEFAULT 0,
    update_by         BIGINT        NOT NULL DEFAULT 0,
    version           INT           NOT NULL DEFAULT 0,
    UNIQUE KEY uk_biz_no (biz_no),
    KEY idx_order (charge_order_id),
    KEY idx_settle_time (settle_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='医保结算';
