-- =====================================================================
-- 压测数据：3000 名虚构学生的患者档案（学号 P00001 ~ P03000），收费员按学号检索后划价收费。
-- 重复执行安全（INSERT IGNORE）。
-- =====================================================================
SET SESSION cte_max_recursion_depth = 100000;

INSERT IGNORE INTO patient (id, campus_id, patient_no, name, gender, birth_date, patient_type, school_no,
                            insurance_type, id_card_enc, id_card_hash, phone_enc, phone_hash, create_time, update_time)
WITH RECURSIVE seq (n) AS (SELECT 1 UNION ALL SELECT n + 1 FROM seq WHERE n < 3000)
SELECT 5000000 + n, 1, CONCAT('PERF', LPAD(n, 6, '0')), CONCAT('压测学生', n), 1 + (n % 2), '2005-01-01', 1,
       CONCAT('P', LPAD(n, 5, '0')), 1, UNHEX('00'), SHA2(CONCAT('perf-idcard-', n), 256), UNHEX('00'),
       SHA2(CONCAT('perf-phone-', n), 256), NOW(), NOW()
FROM seq;
