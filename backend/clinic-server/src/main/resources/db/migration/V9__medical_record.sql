-- P8b：门诊病历、ICD-10 诊断、过敏史、处方结构化用法与合理用药审查

CREATE TABLE icd10_dict (
    code        VARCHAR(16)  NOT NULL PRIMARY KEY COMMENT '疾病编码，如 J06.900',
    name        VARCHAR(200) NOT NULL,
    pinyin      VARCHAR(64)  NULL COMMENT '拼音首字母，便于检索',
    status      TINYINT      NOT NULL DEFAULT 1 COMMENT '1启用 0停用',
    create_time DATETIME     NOT NULL,
    update_time DATETIME     NOT NULL,
    KEY idx_name (name)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='ICD-10 疾病分类与代码；正式使用时导入医保版全集';

CREATE TABLE patient_allergy (
    id          BIGINT       NOT NULL PRIMARY KEY,
    patient_id  BIGINT       NOT NULL,
    allergen    VARCHAR(50)  NOT NULL COMMENT '过敏原；药物过敏填药品目录中的过敏分类，如 青霉素类',
    reaction    VARCHAR(100) NULL COMMENT '过敏表现，如 皮疹、过敏性休克',
    severity    TINYINT      NOT NULL DEFAULT 2 COMMENT '1轻 2中 3重',
    status      TINYINT      NOT NULL DEFAULT 1 COMMENT '1有效 0已撤销（录错时撤销，保留痕迹）',
    create_time DATETIME     NOT NULL,
    update_time DATETIME     NOT NULL,
    create_by   BIGINT       NOT NULL DEFAULT 0,
    update_by   BIGINT       NOT NULL DEFAULT 0,
    version     INT          NOT NULL DEFAULT 0,
    KEY idx_patient (patient_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='患者过敏史';

CREATE TABLE medical_record (
    id               BIGINT        NOT NULL PRIMARY KEY,
    registration_id  BIGINT        NOT NULL,
    patient_id       BIGINT        NOT NULL,
    doctor_id        BIGINT        NOT NULL,
    dept_id          BIGINT        NOT NULL,
    chief_complaint  VARCHAR(500)  NOT NULL COMMENT '主诉',
    present_illness  VARCHAR(2000) NULL COMMENT '现病史',
    past_history     VARCHAR(1000) NULL COMMENT '既往史',
    physical_exam    VARCHAR(1000) NULL COMMENT '体格检查',
    auxiliary_exam   VARCHAR(1000) NULL COMMENT '辅助检查',
    treatment_advice VARCHAR(1000) NULL COMMENT '处理意见',
    status           TINYINT       NOT NULL DEFAULT 0 COMMENT '0草稿 1已签名（签名后不可修改）',
    signed_time      DATETIME      NULL,
    create_time      DATETIME      NOT NULL,
    update_time      DATETIME      NOT NULL,
    create_by        BIGINT        NOT NULL DEFAULT 0,
    update_by        BIGINT        NOT NULL DEFAULT 0,
    version          INT           NOT NULL DEFAULT 0,
    UNIQUE KEY uk_registration (registration_id),
    KEY idx_patient (patient_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='门诊病历';

CREATE TABLE medical_record_diagnosis (
    id          BIGINT       NOT NULL PRIMARY KEY,
    record_id   BIGINT       NOT NULL,
    seq         TINYINT      NOT NULL,
    icd_code    VARCHAR(16)  NOT NULL,
    icd_name    VARCHAR(200) NOT NULL COMMENT '下诊断时的名称快照',
    is_primary  TINYINT      NOT NULL DEFAULT 0,
    create_time DATETIME     NOT NULL,
    update_time DATETIME     NOT NULL,
    create_by   BIGINT       NOT NULL DEFAULT 0,
    update_by   BIGINT       NOT NULL DEFAULT 0,
    version     INT          NOT NULL DEFAULT 0,
    UNIQUE KEY uk_record_seq (record_id, seq),
    KEY idx_icd (icd_code)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='门诊诊断';

ALTER TABLE prescription
    ADD COLUMN rx_type         TINYINT      NOT NULL DEFAULT 1 COMMENT '1普通 3儿科 4麻醉/第一类精神 5第二类精神' AFTER status,
    ADD COLUMN diagnosis       VARCHAR(500) NULL COMMENT '开方时的临床诊断快照，打印处方笺用' AFTER rx_type,
    ADD COLUMN check_result    VARCHAR(2000) NULL COMMENT '合理用药审查提示（JSON），医生确认后保存' AFTER diagnosis,
    ADD COLUMN override_reason VARCHAR(200) NULL COMMENT '医生对审查提示的说明' AFTER check_result,
    ADD COLUMN print_count     INT          NOT NULL DEFAULT 0 AFTER override_reason;

ALTER TABLE prescription_item
    ADD COLUMN dose      DECIMAL(10, 3) NULL COMMENT '单次剂量，单位见 dose_unit' AFTER qty,
    ADD COLUMN dose_unit VARCHAR(16)    NULL AFTER dose,
    ADD COLUMN frequency VARCHAR(8)     NULL COMMENT 'QD BID TID QID QN Q8H Q12H QW ST PRN' AFTER dose_unit,
    ADD COLUMN days      SMALLINT       NULL COMMENT '用药天数' AFTER frequency,
    ADD COLUMN route     VARCHAR(16)    NULL COMMENT '给药途径' AFTER days;

INSERT INTO sys_permission (id, parent_id, perm_code, perm_name, perm_type, path, sort, create_time, update_time)
VALUES (702, 700, 'clinic:record', '门诊病历', 1, '/clinic/prescribe', 2, NOW(), NOW()),
       (703, 700, 'clinic:rx:narcotic', '麻醉药品和第一类精神药品处方权', 2, NULL, 3, NOW(), NOW()),
       (903, 900, 'dict:icd:manage', '疾病编码', 1, '/dict/icd', 3, NOW(), NOW());

-- 医生：病历；麻精处方权需经培训考核后由管理员单独授予，默认不给任何角色（管理员除外）
INSERT INTO sys_role_permission (role_id, permission_id)
VALUES (1, 702), (1, 703), (1, 903),
       (4, 702);

-- 校医院门诊常见诊断（ICD-10 类目/亚目）。上线前由病案或医保管理员在“疾病编码”中导入医保版疾病诊断分类与代码全集
INSERT INTO icd10_dict (code, name, pinyin, create_time, update_time)
VALUES ('J00', '急性鼻咽炎[感冒]', 'JXBYYGM', NOW(), NOW()),
       ('J02.9', '急性咽炎', 'JXYY', NOW(), NOW()),
       ('J03.9', '急性扁桃体炎', 'JXBTTY', NOW(), NOW()),
       ('J06.9', '急性上呼吸道感染', 'JXSHXDGR', NOW(), NOW()),
       ('J11.1', '流行性感冒，伴有其他呼吸道表现，病毒未标明', 'LXXGM', NOW(), NOW()),
       ('J20.9', '急性支气管炎', 'JXZQGY', NOW(), NOW()),
       ('J30.4', '变应性鼻炎', 'BYXBY', NOW(), NOW()),
       ('J45.9', '哮喘', 'XC', NOW(), NOW()),
       ('R05', '咳嗽', 'KS', NOW(), NOW()),
       ('R50.9', '发热', 'FR', NOW(), NOW()),
       ('R51', '头痛', 'TT', NOW(), NOW()),
       ('R10.4', '腹痛', 'FT', NOW(), NOW()),
       ('R11', '恶心和呕吐', 'EXHOT', NOW(), NOW()),
       ('K21.9', '胃食管反流病', 'WSGFLB', NOW(), NOW()),
       ('K29.7', '胃炎', 'WY', NOW(), NOW()),
       ('K30', '功能性消化不良', 'GNXXHBL', NOW(), NOW()),
       ('K52.9', '非感染性胃肠炎', 'FGRXWCY', NOW(), NOW()),
       ('A09', '感染性腹泻', 'GRXFX', NOW(), NOW()),
       ('K59.0', '便秘', 'BM', NOW(), NOW()),
       ('K02.9', '龋', 'Q', NOW(), NOW()),
       ('H10.9', '结膜炎', 'JMY', NOW(), NOW()),
       ('H52.1', '近视', 'JS', NOW(), NOW()),
       ('L20.9', '特应性皮炎', 'TYXPY', NOW(), NOW()),
       ('L23.9', '变应性接触性皮炎', 'BYXJCXPY', NOW(), NOW()),
       ('L50.9', '荨麻疹', 'XMZ', NOW(), NOW()),
       ('L70.0', '寻常痤疮', 'XCCC', NOW(), NOW()),
       ('B35.3', '足癣', 'ZX', NOW(), NOW()),
       ('B01.9', '水痘', 'SD', NOW(), NOW()),
       ('B26.9', '流行性腮腺炎', 'LXXSXY', NOW(), NOW()),
       ('M54.5', '下背痛', 'XBT', NOW(), NOW()),
       ('M79.1', '肌痛', 'JT', NOW(), NOW()),
       ('S93.4', '踝扭伤和劳损', 'HNSHLS', NOW(), NOW()),
       ('T14.0', '浅表损伤', 'QBSS', NOW(), NOW()),
       ('T78.4', '变态反应', 'BTFY', NOW(), NOW()),
       ('N39.0', '泌尿道感染', 'MNDGR', NOW(), NOW()),
       ('N94.6', '痛经', 'TJ', NOW(), NOW()),
       ('G47.0', '失眠', 'SM', NOW(), NOW()),
       ('I10', '特发性（原发性）高血压', 'TFXGXY', NOW(), NOW()),
       ('E11.9', '2型糖尿病，不伴有并发症', 'XTNB', NOW(), NOW()),
       ('D50.9', '缺铁性贫血', 'QTXPX', NOW(), NOW()),
       ('Z00.0', '一般性医学检查', 'YBXYXJC', NOW(), NOW()),
       ('Z23', '需要预防接种', 'XYYFJZ', NOW(), NOW());
