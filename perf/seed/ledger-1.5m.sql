-- =====================================================================
-- M8 造数：150 万条资金流水，分布在 2026-08-01 ~ 2026-08-30（约 5 万条/天，
-- 相当于校医院真实日流水的数百倍，用来放大“扫明细 vs 查预聚合”的差距）。
-- 每 20 条中 1 条为退费（负数），渠道、项目类型、患者类型轮换。
-- =====================================================================
DROP TABLE IF EXISTS perf_digits;
CREATE TABLE perf_digits (d INT PRIMARY KEY);
INSERT INTO perf_digits VALUES (0), (1), (2), (3), (4), (5), (6), (7), (8), (9);

INSERT INTO fund_ledger (id, ledger_date, campus_id, shift_id, biz_type, biz_no, channel, item_type, patient_type,
                         amount, insurance_amount, create_time)
SELECT 8000000000 + n,
       DATE_ADD('2026-08-01', INTERVAL n % 30 DAY),
       1 + n % 2,
       NULL,
       IF(n % 20 = 0, 2, 1),
       CONCAT('PERF', n),
       ELT(1 + n % 3, 1, 3, 4),
       ELT(1 + n % 5, 1, 2, 3, 4, 7),
       1 + n % 3,
       IF(n % 20 = 0, -2.00, 2.00 + n % 17),
       IF(n % 20 = 0, -8.00, 8.00),
       '2026-08-01 12:00:00'
FROM (SELECT a.d + b.d * 10 + c.d * 100 + e.d * 1000 + f.d * 10000 + g.d * 100000 + h.d * 1000000 AS n
      FROM perf_digits a, perf_digits b, perf_digits c, perf_digits e, perf_digits f, perf_digits g, perf_digits h
      WHERE h.d < 2) t
WHERE n < 1500000;

DROP TABLE perf_digits;
