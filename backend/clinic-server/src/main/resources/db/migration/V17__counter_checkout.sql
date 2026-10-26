-- 窗口结算先锁单再收钱：
--   counter_checkout：一次窗口结算尝试。同一张收费单同时只能有一条“进行中”的记录（生成列唯一索引），
--                     另一个窗口再点收费会直接被拒绝，不会去扣校园卡或收现金
--   counter_checkout_tender：这次结算里每种收款方式的收取与撤回状态。撤回失败、或结算中途程序中断的，
--                     进入“待人工处理”，由财务在界面上核对后标记已处理
CREATE TABLE counter_checkout (
    id               BIGINT       NOT NULL PRIMARY KEY,
    charge_order_id  BIGINT       NOT NULL,
    cashier_id       BIGINT       NOT NULL,
    idempotency_key  VARCHAR(64)  NOT NULL COMMENT '收费员客户端的幂等键（业务失败后可沿用，不唯一）',
    status           TINYINT      NOT NULL COMMENT '0进行中 1已完成 2已撤回 3待人工处理 4已人工处理',
    fail_reason      VARCHAR(200) NULL,
    resolve_note     VARCHAR(200) NULL COMMENT '财务人工处理说明',
    resolver_id      BIGINT       NULL,
    active_charge_id BIGINT AS (IF(status = 0, charge_order_id, NULL)) VIRTUAL,
    create_time      DATETIME     NOT NULL,
    update_time      DATETIME     NOT NULL,
    create_by        BIGINT       NOT NULL DEFAULT 0,
    update_by        BIGINT       NOT NULL DEFAULT 0,
    version          INT          NOT NULL DEFAULT 0,
    UNIQUE KEY uk_one_active_checkout (active_charge_id),
    KEY idx_order (charge_order_id),
    KEY idx_status_time (status, create_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='窗口结算尝试（锁单）';

CREATE TABLE counter_checkout_tender (
    id               BIGINT        NOT NULL PRIMARY KEY,
    checkout_id      BIGINT        NOT NULL,
    seq              TINYINT       NOT NULL,
    pay_no           VARCHAR(32)   NOT NULL COMMENT '本方式对应的支付单号（成功后登记到 pay_order）',
    channel          TINYINT       NOT NULL,
    amount           DECIMAL(12,2) NOT NULL,
    status           TINYINT       NOT NULL COMMENT '0收取中 1已收 2已撤回 3撤回失败',
    channel_trade_no VARCHAR(64)   NULL,
    tender_ref       VARCHAR(64)   NULL,
    create_time      DATETIME      NOT NULL,
    update_time      DATETIME      NOT NULL,
    create_by        BIGINT        NOT NULL DEFAULT 0,
    update_by        BIGINT        NOT NULL DEFAULT 0,
    version          INT           NOT NULL DEFAULT 0,
    CONSTRAINT ck_checkout_tender_amount CHECK (amount > 0),
    UNIQUE KEY uk_checkout_seq (checkout_id, seq),
    UNIQUE KEY uk_pay_no (pay_no)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='窗口结算各收款方式的收取与撤回';
