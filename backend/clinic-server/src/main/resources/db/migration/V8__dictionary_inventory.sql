-- =====================================================================
-- V8 字典维护与药库管理
--   药品：医保编码、批准文号、剂量信息（处方审核用）、过敏分类、管制分类
--   收费项目：医保编码
--   库存调整单：盘点、报损，双人复核后才改库存
-- =====================================================================

ALTER TABLE drug
    ADD COLUMN insurance_code    VARCHAR(32)   NULL COMMENT '国家医保药品分类与代码（医保目录对照）' AFTER drug_code,
    ADD COLUMN approval_no       VARCHAR(32)   NULL COMMENT '批准文号，如 国药准字H20000000' AFTER manufacturer,
    ADD COLUMN therapeutic_class VARCHAR(32)   NULL COMMENT '药理分类，用于重复用药提示' AFTER approval_no,
    ADD COLUMN allergy_class     VARCHAR(32)   NULL COMMENT '过敏分类，如 青霉素类，与患者过敏史比对' AFTER therapeutic_class,
    ADD COLUMN controlled_class  TINYINT       NOT NULL DEFAULT 0 COMMENT '0 普通 1 第二类精神药品 2 麻醉/第一类精神药品 3 医疗用毒性药品' AFTER allergy_class,
    ADD COLUMN dose_unit         VARCHAR(16)   NULL COMMENT '最小剂量单位，如 粒、片、袋、ml' AFTER controlled_class,
    ADD COLUMN pack_qty          INT           NULL COMMENT '每个包装单位含多少最小剂量单位，如 一盒 24 粒' AFTER dose_unit,
    ADD COLUMN max_single_dose   DECIMAL(10,2) NULL COMMENT '成人单次最大剂量（最小剂量单位）' AFTER pack_qty,
    ADD COLUMN max_daily_dose    DECIMAL(10,2) NULL COMMENT '成人每日最大剂量（最小剂量单位）' AFTER max_single_dose,
    ADD COLUMN default_usage     VARCHAR(100)  NULL COMMENT '常用用法，开方时默认带出' AFTER max_daily_dose,
    ADD CONSTRAINT ck_drug_pack CHECK (pack_qty IS NULL OR pack_qty > 0),
    ADD KEY idx_insurance_code (insurance_code);

ALTER TABLE price_item
    ADD COLUMN insurance_code VARCHAR(32) NULL COMMENT '医疗服务项目编码（医保目录对照）' AFTER item_name;

CREATE TABLE stock_adjust_order (
    id           BIGINT       NOT NULL PRIMARY KEY,
    adjust_no    VARCHAR(32)  NOT NULL,
    campus_id    BIGINT       NOT NULL,
    adjust_type  TINYINT      NOT NULL COMMENT '1 盘点 2 报损',
    status       TINYINT      NOT NULL COMMENT '0 待复核 1 已生效 2 已驳回',
    reason       VARCHAR(200) NOT NULL,
    reviewer_id  BIGINT       NULL COMMENT '复核人，不能是创建人',
    review_time  DATETIME     NULL,
    review_note  VARCHAR(200) NULL,
    create_time  DATETIME     NOT NULL,
    update_time  DATETIME     NOT NULL,
    create_by    BIGINT       NOT NULL DEFAULT 0,
    update_by    BIGINT       NOT NULL DEFAULT 0,
    version      INT          NOT NULL DEFAULT 0,
    UNIQUE KEY uk_adjust_no (adjust_no),
    KEY idx_campus_status (campus_id, status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='库存调整单（盘点、报损）';

CREATE TABLE stock_adjust_item (
    id          BIGINT       NOT NULL PRIMARY KEY,
    order_id    BIGINT       NOT NULL,
    batch_id    BIGINT       NOT NULL,
    drug_id     BIGINT       NOT NULL,
    book_qty    INT          NOT NULL COMMENT '登记时的账面在库量',
    actual_qty  INT          NOT NULL COMMENT '盘点实存；报损时为账面减报损数',
    delta       INT          NOT NULL COMMENT '实存 − 账面；生效时按差额调整，期间发药不影响结果',
    note        VARCHAR(100) NULL,
    create_time DATETIME     NOT NULL,
    update_time DATETIME     NOT NULL,
    create_by   BIGINT       NOT NULL DEFAULT 0,
    update_by   BIGINT       NOT NULL DEFAULT 0,
    version     INT          NOT NULL DEFAULT 0,
    CONSTRAINT ck_adjust_actual CHECK (actual_qty >= 0),
    UNIQUE KEY uk_order_batch (order_id, batch_id),
    KEY idx_batch (batch_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='库存调整明细';

INSERT INTO sys_permission (id, parent_id, perm_code, perm_name, perm_type, path, sort, create_time, update_time)
VALUES (404, 400, 'pharmacy:stock:approve', '盘点报损复核', 1, '/pharmacy/adjust', 3, NOW(), NOW()),
       (900, 0, 'dict', '字典维护', 1, '/dict', 85, NOW(), NOW()),
       (901, 900, 'dict:drug:manage', '药品目录', 1, '/dict/drugs', 1, NOW(), NOW()),
       (902, 900, 'dict:price:manage', '收费项目', 1, '/dict/prices', 2, NOW(), NOW());

INSERT INTO sys_role_permission (role_id, permission_id)
VALUES (1, 404), (1, 900), (1, 901), (1, 902),
       (3, 404), (3, 900), (3, 901),
       (5, 900), (5, 902);
