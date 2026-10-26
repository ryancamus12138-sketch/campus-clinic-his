-- 按开单科室核算收入（《医院信息系统基本功能规范》门急诊划价收费分系统：日结、月结完成科室核算汇总）
ALTER TABLE charge_order ADD COLUMN dept_id BIGINT NULL COMMENT '开单科室；收费处直接划价的为空' AFTER campus_id;
ALTER TABLE fund_ledger ADD COLUMN dept_id BIGINT NULL COMMENT '开单科室' AFTER campus_id;
