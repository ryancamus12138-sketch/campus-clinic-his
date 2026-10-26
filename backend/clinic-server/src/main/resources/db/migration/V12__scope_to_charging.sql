-- 按项目主题（校医院收费系统）回收 V8、V9 中超出收费范围的部分：
--   门诊病历、ICD-10、过敏史、处方审查与处方笺（属于电子病历与处方审核）
--   盘点报损（属于药库物资管理）
-- 保留：药品与收费项目的医保编码、批准文号（目录对照），收费项目调价版本。

DELETE FROM sys_role_permission WHERE permission_id IN (404, 702, 703, 903);
DELETE FROM sys_permission WHERE id IN (404, 702, 703, 903);

DROP TABLE medical_record_diagnosis;
DROP TABLE medical_record;
DROP TABLE patient_allergy;
DROP TABLE icd10_dict;
DROP TABLE stock_adjust_item;
DROP TABLE stock_adjust_order;

ALTER TABLE prescription
    DROP COLUMN rx_type,
    DROP COLUMN diagnosis,
    DROP COLUMN check_result,
    DROP COLUMN override_reason,
    DROP COLUMN print_count;

ALTER TABLE prescription_item
    DROP COLUMN dose,
    DROP COLUMN dose_unit,
    DROP COLUMN frequency,
    DROP COLUMN days,
    DROP COLUMN route;

ALTER TABLE drug
    DROP CHECK ck_drug_pack,
    DROP COLUMN therapeutic_class,
    DROP COLUMN allergy_class,
    DROP COLUMN controlled_class,
    DROP COLUMN dose_unit,
    DROP COLUMN pack_qty,
    DROP COLUMN max_single_dose,
    DROP COLUMN max_daily_dose,
    DROP COLUMN default_usage;
