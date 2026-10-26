-- =====================================================================
-- 校医院门诊收费系统：初始表结构（MySQL 8.0）
--
-- 全局约定（详见 docs/design.md 第 4 章）：
--   * 主键 BIGINT 雪花 ID；对外展示用带日期前缀的业务编号
--   * 金额 DECIMAL(12,2) + CHECK >= 0；数量 INT + CHECK
--   * 枚举用 TINYINT 编码，新增取值不改表
--   * 逻辑外键：只建索引，不建 FOREIGN KEY（避免子表插入对父表加共享锁，
--     与号源扣减、库存扣减的排他锁冲突）
--   * 资金、库存类表不删除，只改状态；大流水表按时间分区
--   * 生成列 + 唯一索引实现“同一时刻只能有一条有效记录”
-- =====================================================================

-- ---------------------------------------------------------------------
-- 一、系统与权限
-- ---------------------------------------------------------------------
CREATE TABLE sys_campus (
    id          BIGINT      NOT NULL PRIMARY KEY,
    name        VARCHAR(50) NOT NULL COMMENT '院区名称',
    address     VARCHAR(200) NULL,
    create_time DATETIME    NOT NULL,
    update_time DATETIME    NOT NULL,
    create_by   BIGINT      NOT NULL DEFAULT 0,
    update_by   BIGINT      NOT NULL DEFAULT 0,
    version     INT         NOT NULL DEFAULT 0,
    UNIQUE KEY uk_name (name)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='院区';

CREATE TABLE sys_dept (
    id          BIGINT      NOT NULL PRIMARY KEY,
    campus_id   BIGINT      NOT NULL,
    dept_code   VARCHAR(32) NOT NULL,
    dept_name   VARCHAR(50) NOT NULL,
    dept_type   TINYINT     NOT NULL COMMENT '1临床科室 2医技 3药房 4收费处 5行政',
    status      TINYINT     NOT NULL DEFAULT 1 COMMENT '1启用 0停用',
    sort        INT         NOT NULL DEFAULT 0,
    create_time DATETIME    NOT NULL,
    update_time DATETIME    NOT NULL,
    create_by   BIGINT      NOT NULL DEFAULT 0,
    update_by   BIGINT      NOT NULL DEFAULT 0,
    version     INT         NOT NULL DEFAULT 0,
    UNIQUE KEY uk_campus_code (campus_id, dept_code)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='科室';

CREATE TABLE sys_user (
    id              BIGINT       NOT NULL PRIMARY KEY,
    username        VARCHAR(50)  NOT NULL,
    password_hash   VARCHAR(100) NOT NULL COMMENT 'BCrypt',
    nickname        VARCHAR(50)  NOT NULL,
    user_type       TINYINT      NOT NULL COMMENT '1员工 2患者',
    campus_id       BIGINT       NULL,
    dept_id         BIGINT       NULL,
    patient_id      BIGINT       NULL COMMENT '患者账号关联的患者档案',
    status          TINYINT      NOT NULL DEFAULT 1 COMMENT '1启用 0停用',
    last_login_time DATETIME     NULL,
    create_time     DATETIME     NOT NULL,
    update_time     DATETIME     NOT NULL,
    create_by       BIGINT       NOT NULL DEFAULT 0,
    update_by       BIGINT       NOT NULL DEFAULT 0,
    version         INT          NOT NULL DEFAULT 0,
    UNIQUE KEY uk_username (username),
    UNIQUE KEY uk_patient (patient_id),
    KEY idx_dept (dept_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='系统用户';

CREATE TABLE sys_role (
    id          BIGINT      NOT NULL PRIMARY KEY,
    role_code   VARCHAR(32) NOT NULL,
    role_name   VARCHAR(50) NOT NULL,
    data_scope  TINYINT     NOT NULL DEFAULT 3 COMMENT '1全部 2本院区 3仅本人',
    status      TINYINT     NOT NULL DEFAULT 1,
    create_time DATETIME    NOT NULL,
    update_time DATETIME    NOT NULL,
    create_by   BIGINT      NOT NULL DEFAULT 0,
    update_by   BIGINT      NOT NULL DEFAULT 0,
    version     INT         NOT NULL DEFAULT 0,
    UNIQUE KEY uk_role_code (role_code)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='角色';

CREATE TABLE sys_permission (
    id          BIGINT      NOT NULL PRIMARY KEY,
    parent_id   BIGINT      NOT NULL DEFAULT 0,
    perm_code   VARCHAR(64) NOT NULL COMMENT '如 charge:settle',
    perm_name   VARCHAR(50) NOT NULL,
    perm_type   TINYINT     NOT NULL COMMENT '1菜单 2按钮 3接口',
    path        VARCHAR(100) NULL COMMENT '前端路由',
    sort        INT         NOT NULL DEFAULT 0,
    create_time DATETIME    NOT NULL,
    update_time DATETIME    NOT NULL,
    create_by   BIGINT      NOT NULL DEFAULT 0,
    update_by   BIGINT      NOT NULL DEFAULT 0,
    version     INT         NOT NULL DEFAULT 0,
    UNIQUE KEY uk_perm_code (perm_code)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='权限';

CREATE TABLE sys_user_role (
    user_id BIGINT NOT NULL,
    role_id BIGINT NOT NULL,
    PRIMARY KEY (user_id, role_id),
    KEY idx_role (role_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='用户角色';

CREATE TABLE sys_role_permission (
    role_id       BIGINT NOT NULL,
    permission_id BIGINT NOT NULL,
    PRIMARY KEY (role_id, permission_id),
    KEY idx_permission (permission_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='角色权限';

CREATE TABLE operate_log (
    id          BIGINT       NOT NULL,
    user_id     BIGINT       NOT NULL,
    module      VARCHAR(32)  NOT NULL,
    action      VARCHAR(64)  NOT NULL,
    biz_no      VARCHAR(32)  NULL,
    request_ip  VARCHAR(45)  NULL,
    params      JSON         NULL COMMENT '已脱敏',
    success     TINYINT      NOT NULL,
    cost_ms     INT          NOT NULL,
    trace_id    VARCHAR(64)  NULL,
    create_time DATETIME     NOT NULL,
    PRIMARY KEY (id, create_time),
    KEY idx_user_time (user_id, create_time),
    KEY idx_biz_no (biz_no)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='操作日志'
    PARTITION BY RANGE COLUMNS (create_time) (
        PARTITION p2026h1 VALUES LESS THAN ('2026-07-01'),
        PARTITION p2026h2 VALUES LESS THAN ('2027-01-01'),
        PARTITION pmax VALUES LESS THAN (MAXVALUE)
        );

-- ---------------------------------------------------------------------
-- 二、患者与预约
-- ---------------------------------------------------------------------
CREATE TABLE patient (
    id             BIGINT         NOT NULL PRIMARY KEY,
    campus_id      BIGINT         NOT NULL,
    patient_no     VARCHAR(32)    NOT NULL COMMENT '就诊卡号',
    name           VARCHAR(50)    NOT NULL,
    gender         TINYINT        NOT NULL COMMENT '1男 2女',
    birth_date     DATE           NOT NULL,
    patient_type   TINYINT        NOT NULL COMMENT '1学生 2教职工 3其他',
    school_no      VARCHAR(20)    NULL COMMENT '学号/工号',
    insurance_type TINYINT        NOT NULL DEFAULT 0 COMMENT '0自费 1大学生医保 2职工医保 3公费医疗',
    id_card_enc    VARBINARY(128) NOT NULL COMMENT 'AES 密文',
    id_card_hash   CHAR(64)       NOT NULL COMMENT 'SHA-256，用于唯一约束和精确检索',
    phone_enc      VARBINARY(64)  NOT NULL,
    phone_hash     CHAR(64)       NOT NULL,
    create_time    DATETIME       NOT NULL,
    update_time    DATETIME       NOT NULL,
    create_by      BIGINT         NOT NULL DEFAULT 0,
    update_by      BIGINT         NOT NULL DEFAULT 0,
    version        INT            NOT NULL DEFAULT 0,
    UNIQUE KEY uk_patient_no (patient_no),
    UNIQUE KEY uk_id_card (id_card_hash),
    UNIQUE KEY uk_school_no (school_no),
    KEY idx_phone (phone_hash)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='患者档案';

CREATE TABLE schedule_slot (
    id              BIGINT      NOT NULL PRIMARY KEY,
    campus_id       BIGINT      NOT NULL,
    dept_id         BIGINT      NOT NULL,
    doctor_id       BIGINT      NOT NULL DEFAULT 0 COMMENT '体检、疫苗时段为 0',
    slot_type       TINYINT     NOT NULL COMMENT '1门诊 2新生体检 3疫苗',
    slot_date       DATE        NOT NULL,
    start_time      TIME        NOT NULL,
    end_time        TIME        NOT NULL,
    total           INT         NOT NULL,
    remaining       INT         NOT NULL,
    price_item_code VARCHAR(32) NOT NULL,
    status          TINYINT     NOT NULL COMMENT '0未开放 1开放 2停诊',
    open_time       DATETIME    NOT NULL COMMENT '放号时间',
    create_time     DATETIME    NOT NULL,
    update_time     DATETIME    NOT NULL,
    create_by       BIGINT      NOT NULL DEFAULT 0,
    update_by       BIGINT      NOT NULL DEFAULT 0,
    version         INT         NOT NULL DEFAULT 0,
    CONSTRAINT ck_slot_remaining CHECK (remaining >= 0 AND remaining <= total),
    CONSTRAINT ck_slot_time CHECK (end_time > start_time),
    UNIQUE KEY uk_slot (campus_id, dept_id, slot_type, slot_date, start_time, doctor_id),
    KEY idx_date_dept (slot_date, dept_id, status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='号源';

CREATE TABLE registration (
    id              BIGINT      NOT NULL PRIMARY KEY,
    reg_no          VARCHAR(32) NOT NULL,
    patient_id      BIGINT      NOT NULL,
    slot_id         BIGINT      NOT NULL,
    status          TINYINT     NOT NULL COMMENT '0待支付 1已预约 2已就诊 3已取消 4已过期',
    charge_order_id BIGINT      NULL,
    expire_time     DATETIME    NOT NULL COMMENT '未支付自动释放时间',
    -- 只有待支付/已预约算有效；其余为 NULL，唯一索引对 NULL 不生效，取消后可再预约
    active_slot_id  BIGINT AS (IF(status IN (0, 1), slot_id, NULL)) VIRTUAL,
    create_time     DATETIME    NOT NULL,
    update_time     DATETIME    NOT NULL,
    create_by       BIGINT      NOT NULL DEFAULT 0,
    update_by       BIGINT      NOT NULL DEFAULT 0,
    version         INT         NOT NULL DEFAULT 0,
    UNIQUE KEY uk_reg_no (reg_no),
    UNIQUE KEY uk_patient_active_slot (patient_id, active_slot_id),
    KEY idx_slot (slot_id),
    KEY idx_status_expire (status, expire_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='挂号/预约';

CREATE TABLE referral (
    id              BIGINT       NOT NULL PRIMARY KEY,
    referral_no     VARCHAR(32)  NOT NULL,
    patient_id      BIGINT       NOT NULL,
    registration_id BIGINT       NOT NULL COMMENT '开具转诊单的那次就诊',
    doctor_id       BIGINT       NOT NULL,
    target_hospital VARCHAR(100) NOT NULL,
    reason          VARCHAR(200) NOT NULL,
    valid_until     DATE         NOT NULL COMMENT '有效期 7 天',
    status          TINYINT      NOT NULL COMMENT '1有效 2已使用 3已作废',
    create_time     DATETIME     NOT NULL,
    update_time     DATETIME     NOT NULL,
    create_by       BIGINT       NOT NULL DEFAULT 0,
    update_by       BIGINT       NOT NULL DEFAULT 0,
    version         INT          NOT NULL DEFAULT 0,
    UNIQUE KEY uk_referral_no (referral_no),
    KEY idx_patient (patient_id, create_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='转诊单';

-- ---------------------------------------------------------------------
-- 三、价目、处方与收费
-- ---------------------------------------------------------------------
CREATE TABLE price_item (
    id              BIGINT        NOT NULL PRIMARY KEY,
    item_code       VARCHAR(32)   NOT NULL,
    item_name       VARCHAR(100)  NOT NULL,
    item_type       TINYINT       NOT NULL COMMENT '1挂号 3诊疗 4检查 6体检 7疫苗服务',
    unit            VARCHAR(16)   NOT NULL,
    unit_price      DECIMAL(12,2) NOT NULL,
    insurance_class TINYINT       NOT NULL COMMENT '0不可报销 1可报销',
    effective_from  DATETIME      NOT NULL,
    effective_to    DATETIME      NULL COMMENT 'NULL 表示当前生效',
    create_time     DATETIME      NOT NULL,
    update_time     DATETIME      NOT NULL,
    create_by       BIGINT        NOT NULL DEFAULT 0,
    update_by       BIGINT        NOT NULL DEFAULT 0,
    version         INT           NOT NULL DEFAULT 0,
    CONSTRAINT ck_price CHECK (unit_price >= 0),
    UNIQUE KEY uk_code_from (item_code, effective_from),
    KEY idx_code_to (item_code, effective_to)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='价目表（价格分版本）';

CREATE TABLE prescription (
    id              BIGINT      NOT NULL PRIMARY KEY,
    rx_no           VARCHAR(32) NOT NULL,
    registration_id BIGINT      NOT NULL,
    patient_id      BIGINT      NOT NULL,
    doctor_id       BIGINT      NOT NULL,
    status          TINYINT     NOT NULL COMMENT '0已开立 1已划价 2已撤销',
    create_time     DATETIME    NOT NULL,
    update_time     DATETIME    NOT NULL,
    create_by       BIGINT      NOT NULL DEFAULT 0,
    update_by       BIGINT      NOT NULL DEFAULT 0,
    version         INT         NOT NULL DEFAULT 0,
    UNIQUE KEY uk_rx_no (rx_no),
    KEY idx_reg (registration_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='处方/医嘱';

CREATE TABLE prescription_item (
    id          BIGINT       NOT NULL PRIMARY KEY,
    rx_id       BIGINT       NOT NULL,
    item_kind   TINYINT      NOT NULL COMMENT '1药品/卫生材料 2诊疗项目',
    goods_id    BIGINT       NULL COMMENT '药品或卫生材料',
    item_code   VARCHAR(32)  NULL COMMENT '诊疗项目价目编码',
    qty         INT          NOT NULL,
    usage_note  VARCHAR(100) NULL,
    create_time DATETIME     NOT NULL,
    update_time DATETIME     NOT NULL,
    create_by   BIGINT       NOT NULL DEFAULT 0,
    update_by   BIGINT       NOT NULL DEFAULT 0,
    version     INT          NOT NULL DEFAULT 0,
    CONSTRAINT ck_rx_qty CHECK (qty > 0),
    KEY idx_rx (rx_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='处方明细';

CREATE TABLE charge_order (
    id               BIGINT        NOT NULL PRIMARY KEY,
    charge_no        VARCHAR(32)   NOT NULL,
    campus_id        BIGINT        NOT NULL,
    patient_id       BIGINT        NOT NULL,
    registration_id  BIGINT        NULL,
    source_type      TINYINT       NOT NULL COMMENT '1挂号 2门诊处方 3体检 4疫苗',
    status           TINYINT       NOT NULL COMMENT '0待支付 1部分支付 2已支付 3部分退费 4已全退 5已关闭',
    total_amount     DECIMAL(12,2) NOT NULL COMMENT '原价合计',
    insurance_amount DECIMAL(12,2) NOT NULL DEFAULT 0 COMMENT '医保记账',
    discount_amount  DECIMAL(12,2) NOT NULL DEFAULT 0,
    payable_amount   DECIMAL(12,2) NOT NULL COMMENT '个人应付',
    paid_amount      DECIMAL(12,2) NOT NULL DEFAULT 0,
    refunded_amount  DECIMAL(12,2) NOT NULL DEFAULT 0,
    fee_policy_code  VARCHAR(32)   NOT NULL COMMENT '计费时使用的策略（快照）',
    expire_time      DATETIME      NOT NULL,
    paid_time        DATETIME      NULL,
    cashier_id       BIGINT        NULL,
    shift_id         BIGINT        NULL,
    create_time      DATETIME      NOT NULL,
    update_time      DATETIME      NOT NULL,
    create_by        BIGINT        NOT NULL DEFAULT 0,
    update_by        BIGINT        NOT NULL DEFAULT 0,
    version          INT           NOT NULL DEFAULT 0,
    CONSTRAINT ck_order_nonneg CHECK (total_amount >= 0 AND insurance_amount >= 0 AND discount_amount >= 0),
    CONSTRAINT ck_order_split CHECK (payable_amount = total_amount - insurance_amount - discount_amount),
    CONSTRAINT ck_order_paid CHECK (paid_amount >= 0 AND paid_amount <= payable_amount),
    CONSTRAINT ck_order_refund CHECK (refunded_amount >= 0 AND refunded_amount <= paid_amount),
    UNIQUE KEY uk_charge_no (charge_no),
    KEY idx_patient_time (patient_id, create_time),
    KEY idx_status_expire (status, expire_time),
    KEY idx_shift (shift_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='收费单';

CREATE TABLE charge_order_item (
    id               BIGINT        NOT NULL PRIMARY KEY,
    charge_order_id  BIGINT        NOT NULL,
    item_type        TINYINT       NOT NULL COMMENT '1挂号 2药品 3诊疗 4检查 5卫生材料 6体检 7疫苗服务',
    ref_type         TINYINT       NOT NULL COMMENT '来源：1挂号 2处方明细 3体检预约 4疫苗预约',
    ref_id           BIGINT        NOT NULL,
    item_code        VARCHAR(32)   NOT NULL,
    item_name        VARCHAR(100)  NOT NULL COMMENT '快照',
    unit_price       DECIMAL(12,2) NOT NULL COMMENT '快照',
    qty              INT           NOT NULL,
    amount           DECIMAL(12,2) NOT NULL,
    insurance_ratio  DECIMAL(5,4)  NOT NULL COMMENT '快照，如 0.8000',
    insurance_amount DECIMAL(12,2) NOT NULL,
    self_amount      DECIMAL(12,2) NOT NULL,
    refunded_qty     INT           NOT NULL DEFAULT 0,
    dispensed_qty    INT           NOT NULL DEFAULT 0,
    valid            TINYINT       NOT NULL DEFAULT 1 COMMENT '所属收费单关闭后置 0',
    active_ref_id    BIGINT AS (IF(valid = 1, ref_id, NULL)) VIRTUAL,
    create_time      DATETIME      NOT NULL,
    update_time      DATETIME      NOT NULL,
    create_by        BIGINT        NOT NULL DEFAULT 0,
    update_by        BIGINT        NOT NULL DEFAULT 0,
    version          INT           NOT NULL DEFAULT 0,
    CONSTRAINT ck_item_amount CHECK (amount = unit_price * qty AND amount = insurance_amount + self_amount),
    CONSTRAINT ck_item_qty CHECK (qty > 0 AND refunded_qty >= 0 AND dispensed_qty >= 0
        AND refunded_qty + dispensed_qty <= qty),
    UNIQUE KEY uk_active_ref (ref_type, active_ref_id),
    KEY idx_order (charge_order_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='收费明细';

-- ---------------------------------------------------------------------
-- 四、支付、退费、票据
-- ---------------------------------------------------------------------
CREATE TABLE pay_order (
    id               BIGINT        NOT NULL PRIMARY KEY,
    pay_no           VARCHAR(32)   NOT NULL,
    charge_order_id  BIGINT        NOT NULL,
    channel          TINYINT       NOT NULL COMMENT '1现金 2校园卡 3支付宝 4微信 5医保个人账户',
    amount           DECIMAL(12,2) NOT NULL,
    status           TINYINT       NOT NULL COMMENT '0待支付 1成功 2失败 3已关闭',
    idempotency_key  VARCHAR(64)   NOT NULL,
    channel_trade_no VARCHAR(64)   NULL,
    refunded_amount  DECIMAL(12,2) NOT NULL DEFAULT 0,
    expire_time      DATETIME      NOT NULL,
    success_time     DATETIME      NULL,
    create_time      DATETIME      NOT NULL,
    update_time      DATETIME      NOT NULL,
    create_by        BIGINT        NOT NULL DEFAULT 0,
    update_by        BIGINT        NOT NULL DEFAULT 0,
    version          INT           NOT NULL DEFAULT 0,
    CONSTRAINT ck_pay_amount CHECK (amount > 0 AND refunded_amount >= 0 AND refunded_amount <= amount),
    UNIQUE KEY uk_pay_no (pay_no),
    UNIQUE KEY uk_idem (idempotency_key),
    UNIQUE KEY uk_channel_trade (channel, channel_trade_no),
    KEY idx_charge (charge_order_id),
    KEY idx_status_create (status, create_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='支付单';

CREATE TABLE refund_order (
    id                BIGINT        NOT NULL PRIMARY KEY,
    refund_no         VARCHAR(32)   NOT NULL,
    charge_order_id   BIGINT        NOT NULL,
    pay_order_id      BIGINT        NOT NULL COMMENT '原路退回的支付单',
    amount            DECIMAL(12,2) NOT NULL,
    reason            VARCHAR(200)  NOT NULL,
    status            TINYINT       NOT NULL COMMENT '0待审核 1退款中 2成功 3失败 4驳回',
    idempotency_key   VARCHAR(64)   NOT NULL,
    approver_id       BIGINT        NULL,
    channel_refund_no VARCHAR(64)   NULL,
    create_time       DATETIME      NOT NULL,
    update_time       DATETIME      NOT NULL,
    create_by         BIGINT        NOT NULL DEFAULT 0,
    update_by         BIGINT        NOT NULL DEFAULT 0,
    version           INT           NOT NULL DEFAULT 0,
    CONSTRAINT ck_refund_amount CHECK (amount > 0),
    UNIQUE KEY uk_refund_no (refund_no),
    UNIQUE KEY uk_idem (idempotency_key),
    KEY idx_charge (charge_order_id),
    KEY idx_status (status, create_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='退费单';

CREATE TABLE refund_item (
    id              BIGINT        NOT NULL PRIMARY KEY,
    refund_order_id BIGINT        NOT NULL,
    charge_item_id  BIGINT        NOT NULL,
    qty             INT           NOT NULL,
    amount          DECIMAL(12,2) NOT NULL,
    create_time     DATETIME      NOT NULL,
    update_time     DATETIME      NOT NULL,
    create_by       BIGINT        NOT NULL DEFAULT 0,
    update_by       BIGINT        NOT NULL DEFAULT 0,
    version         INT           NOT NULL DEFAULT 0,
    CONSTRAINT ck_refund_item CHECK (qty > 0 AND amount >= 0),
    KEY idx_refund (refund_order_id),
    KEY idx_charge_item (charge_item_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='退费明细';

CREATE TABLE e_invoice (
    id                  BIGINT        NOT NULL PRIMARY KEY,
    invoice_no          VARCHAR(32)   NULL COMMENT '财政平台返回的票据号',
    charge_order_id     BIGINT        NOT NULL,
    refund_order_id     BIGINT        NULL COMMENT '红票对应的退费单',
    invoice_type        TINYINT       NOT NULL COMMENT '1正票 2红票',
    original_invoice_id BIGINT        NULL COMMENT '红票冲抵的原票',
    amount              DECIMAL(12,2) NOT NULL,
    status              TINYINT       NOT NULL COMMENT '0待开 1已开 2失败',
    biz_key             VARCHAR(64)   NOT NULL COMMENT '幂等键',
    create_time         DATETIME      NOT NULL,
    update_time         DATETIME      NOT NULL,
    create_by           BIGINT        NOT NULL DEFAULT 0,
    update_by           BIGINT        NOT NULL DEFAULT 0,
    version             INT           NOT NULL DEFAULT 0,
    CONSTRAINT ck_invoice_amount CHECK (amount >= 0),
    UNIQUE KEY uk_biz_key (biz_key),
    KEY idx_charge (charge_order_id),
    KEY idx_status (status, create_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='医疗收费电子票据';

-- ---------------------------------------------------------------------
-- 五、药品、卫生材料与库存
-- ---------------------------------------------------------------------
CREATE TABLE drug (
    id              BIGINT        NOT NULL PRIMARY KEY,
    drug_code       VARCHAR(32)   NOT NULL,
    name            VARCHAR(100)  NOT NULL,
    spec            VARCHAR(64)   NOT NULL COMMENT '规格',
    unit            VARCHAR(16)   NOT NULL,
    goods_type      TINYINT       NOT NULL DEFAULT 1 COMMENT '1药品 2卫生材料',
    retail_price    DECIMAL(12,2) NOT NULL,
    insurance_class TINYINT       NOT NULL COMMENT '0不可报销 1可报销',
    trace_required  TINYINT       NOT NULL DEFAULT 1 COMMENT '发药是否必须扫追溯码',
    manufacturer    VARCHAR(100)  NULL,
    safety_stock    INT           NOT NULL DEFAULT 0,
    status          TINYINT       NOT NULL DEFAULT 1,
    create_time     DATETIME      NOT NULL,
    update_time     DATETIME      NOT NULL,
    create_by       BIGINT        NOT NULL DEFAULT 0,
    update_by       BIGINT        NOT NULL DEFAULT 0,
    version         INT           NOT NULL DEFAULT 0,
    CONSTRAINT ck_drug_price CHECK (retail_price >= 0),
    UNIQUE KEY uk_drug_code (drug_code),
    KEY idx_name (name)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='药品与卫生材料目录';

CREATE TABLE drug_batch (
    id             BIGINT        NOT NULL PRIMARY KEY,
    campus_id      BIGINT        NOT NULL,
    drug_id        BIGINT        NOT NULL,
    batch_no       VARCHAR(32)   NOT NULL,
    expire_date    DATE          NOT NULL,
    purchase_price DECIMAL(12,2) NOT NULL,
    qty_on_hand    INT           NOT NULL COMMENT '在库',
    qty_locked     INT           NOT NULL DEFAULT 0 COMMENT '已划价未发药',
    create_time    DATETIME      NOT NULL,
    update_time    DATETIME      NOT NULL,
    create_by      BIGINT        NOT NULL DEFAULT 0,
    update_by      BIGINT        NOT NULL DEFAULT 0,
    version        INT           NOT NULL DEFAULT 0,
    CONSTRAINT ck_batch_qty CHECK (qty_on_hand >= 0 AND qty_locked >= 0 AND qty_locked <= qty_on_hand),
    UNIQUE KEY uk_batch (campus_id, drug_id, batch_no),
    KEY idx_fefo (campus_id, drug_id, expire_date)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='药品批次库存';

CREATE TABLE stock_lock (
    id             BIGINT   NOT NULL PRIMARY KEY,
    charge_item_id BIGINT   NOT NULL,
    batch_id       BIGINT   NOT NULL,
    qty            INT      NOT NULL,
    status         TINYINT  NOT NULL COMMENT '1锁定 2已发药 3已释放 4已退药',
    create_time    DATETIME NOT NULL,
    update_time    DATETIME NOT NULL,
    create_by      BIGINT   NOT NULL DEFAULT 0,
    update_by      BIGINT   NOT NULL DEFAULT 0,
    version        INT      NOT NULL DEFAULT 0,
    CONSTRAINT ck_lock_qty CHECK (qty > 0),
    UNIQUE KEY uk_item_batch (charge_item_id, batch_id),
    KEY idx_batch (batch_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='库存锁定（收费明细与批次的关联）';

CREATE TABLE stock_flow (
    id            BIGINT      NOT NULL,
    batch_id      BIGINT      NOT NULL,
    drug_id       BIGINT      NOT NULL,
    flow_type     TINYINT     NOT NULL COMMENT '1入库 2锁定 3解锁 4发药 5退药 6盘点调整',
    delta_on_hand INT         NOT NULL,
    delta_locked  INT         NOT NULL,
    after_on_hand INT         NOT NULL,
    after_locked  INT         NOT NULL,
    biz_type      TINYINT     NOT NULL,
    biz_no        VARCHAR(32) NOT NULL,
    operator_id   BIGINT      NOT NULL,
    create_time   DATETIME    NOT NULL,
    PRIMARY KEY (id, create_time),
    KEY idx_biz (biz_type, biz_no),
    KEY idx_drug_time (drug_id, create_time),
    KEY idx_batch_time (batch_id, create_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='库存流水（只追加）'
    PARTITION BY RANGE COLUMNS (create_time) (
        PARTITION p2026h1 VALUES LESS THAN ('2026-07-01'),
        PARTITION p2026h2 VALUES LESS THAN ('2027-01-01'),
        PARTITION pmax VALUES LESS THAN (MAXVALUE)
        );

CREATE TABLE drug_trace_code (
    id             BIGINT      NOT NULL PRIMARY KEY,
    trace_code     VARCHAR(64) NOT NULL COMMENT '最小销售包装追溯码',
    drug_id        BIGINT      NOT NULL,
    batch_id       BIGINT      NOT NULL,
    charge_item_id BIGINT      NOT NULL,
    stock_lock_id  BIGINT      NOT NULL,
    status         TINYINT     NOT NULL COMMENT '1已采集 2已上传 3上传失败 4已退药',
    upload_time    DATETIME    NULL,
    retry_count    INT         NOT NULL DEFAULT 0,
    -- 未退药的追溯码全局唯一：同一盒药不能被卖两次
    active_code    VARCHAR(64) AS (IF(status <> 4, trace_code, NULL)) VIRTUAL,
    create_time    DATETIME    NOT NULL,
    update_time    DATETIME    NOT NULL,
    create_by      BIGINT      NOT NULL DEFAULT 0,
    update_by      BIGINT      NOT NULL DEFAULT 0,
    version        INT         NOT NULL DEFAULT 0,
    UNIQUE KEY uk_active_code (active_code),
    KEY idx_item (charge_item_id),
    KEY idx_status (status, upload_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='药品追溯码';

-- ---------------------------------------------------------------------
-- 六、财务、报表与事件
-- ---------------------------------------------------------------------
CREATE TABLE cashier_shift (
    id            BIGINT        NOT NULL PRIMARY KEY,
    shift_no      VARCHAR(32)   NOT NULL,
    campus_id     BIGINT        NOT NULL,
    cashier_id    BIGINT        NOT NULL,
    status        TINYINT       NOT NULL COMMENT '0当班 1已交班 2已复核',
    start_time    DATETIME      NOT NULL,
    end_time      DATETIME      NULL,
    cash_amount   DECIMAL(12,2) NOT NULL DEFAULT 0,
    card_amount   DECIMAL(12,2) NOT NULL DEFAULT 0,
    online_amount DECIMAL(12,2) NOT NULL DEFAULT 0,
    refund_amount DECIMAL(12,2) NOT NULL DEFAULT 0,
    counted_cash  DECIMAL(12,2) NULL COMMENT '收费员实点现金',
    order_count   INT           NOT NULL DEFAULT 0,
    open_cashier  BIGINT AS (IF(status = 0, cashier_id, NULL)) VIRTUAL,
    create_time   DATETIME      NOT NULL,
    update_time   DATETIME      NOT NULL,
    create_by     BIGINT        NOT NULL DEFAULT 0,
    update_by     BIGINT        NOT NULL DEFAULT 0,
    version       INT           NOT NULL DEFAULT 0,
    UNIQUE KEY uk_shift_no (shift_no),
    UNIQUE KEY uk_one_open_shift (open_cashier)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='收费员交班单';

CREATE TABLE fund_ledger (
    id           BIGINT        NOT NULL,
    ledger_date  DATE          NOT NULL COMMENT '记账日期',
    campus_id    BIGINT        NOT NULL,
    shift_id     BIGINT        NULL,
    biz_type     TINYINT       NOT NULL COMMENT '1收费 2退费',
    biz_no       VARCHAR(32)   NOT NULL COMMENT '支付单号或退费单号',
    channel      TINYINT       NOT NULL,
    item_type    TINYINT       NOT NULL,
    patient_type TINYINT       NOT NULL,
    amount       DECIMAL(12,2) NOT NULL COMMENT '收费为正，退费为负',
    create_time  DATETIME      NOT NULL,
    PRIMARY KEY (id, ledger_date),
    UNIQUE KEY uk_biz (biz_type, biz_no, item_type, ledger_date),
    KEY idx_shift (shift_id),
    KEY idx_date_campus (ledger_date, campus_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='资金流水（只追加，退费红冲）'
    PARTITION BY RANGE COLUMNS (ledger_date) (
        PARTITION p2026h1 VALUES LESS THAN ('2026-07-01'),
        PARTITION p2026h2 VALUES LESS THAN ('2027-01-01'),
        PARTITION pmax VALUES LESS THAN (MAXVALUE)
        );

CREATE TABLE recon_diff (
    id               BIGINT        NOT NULL PRIMARY KEY,
    recon_date       DATE          NOT NULL,
    channel          TINYINT       NOT NULL,
    diff_type        TINYINT       NOT NULL COMMENT '1长款(渠道有我无) 2短款(我有渠道无) 3金额不符',
    pay_no           VARCHAR(32)   NULL,
    channel_trade_no VARCHAR(64)   NULL,
    our_amount       DECIMAL(12,2) NULL,
    channel_amount   DECIMAL(12,2) NULL,
    status           TINYINT       NOT NULL COMMENT '0待处理 1已处理',
    create_time      DATETIME      NOT NULL,
    update_time      DATETIME      NOT NULL,
    create_by        BIGINT        NOT NULL DEFAULT 0,
    update_by        BIGINT        NOT NULL DEFAULT 0,
    version          INT           NOT NULL DEFAULT 0,
    UNIQUE KEY uk_diff (recon_date, channel, channel_trade_no, pay_no)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='对账差异';

CREATE TABLE report_daily_agg (
    id               BIGINT        NOT NULL PRIMARY KEY,
    stat_date        DATE          NOT NULL,
    campus_id        BIGINT        NOT NULL,
    item_type        TINYINT       NOT NULL,
    channel          TINYINT       NOT NULL,
    patient_type     TINYINT       NOT NULL,
    order_count      INT           NOT NULL,
    total_amount     DECIMAL(14,2) NOT NULL,
    insurance_amount DECIMAL(14,2) NOT NULL,
    self_amount      DECIMAL(14,2) NOT NULL,
    refund_amount    DECIMAL(14,2) NOT NULL,
    UNIQUE KEY uk_dim (stat_date, campus_id, item_type, channel, patient_type)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='日报预聚合';

CREATE TABLE outbox_message (
    id              BIGINT      NOT NULL PRIMARY KEY,
    event_type      VARCHAR(64) NOT NULL,
    aggregate_id    BIGINT      NOT NULL,
    payload         JSON        NOT NULL,
    status          TINYINT     NOT NULL COMMENT '0待投递 1已投递 2死信',
    retry_count     INT         NOT NULL DEFAULT 0,
    next_retry_time DATETIME    NOT NULL,
    create_time     DATETIME    NOT NULL,
    KEY idx_dispatch (status, next_retry_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='事务性发件箱';

CREATE TABLE event_consume_record (
    event_id    BIGINT      NOT NULL,
    consumer    VARCHAR(64) NOT NULL,
    create_time DATETIME    NOT NULL,
    PRIMARY KEY (event_id, consumer)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='事件消费记录（幂等）';
