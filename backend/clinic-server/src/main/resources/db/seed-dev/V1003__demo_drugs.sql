-- =====================================================================
-- 演示药品与批次（仅 dev、test）。价格为示意零售价。
-- 同一药品放两个效期不同的批次，用于演示“先到期先出”（FEFO）。
-- =====================================================================
INSERT INTO drug (id, drug_code, name, spec, unit, goods_type, retail_price, insurance_class, trace_required,
                  manufacturer, safety_stock, status, create_time, update_time)
VALUES (4001, 'YP-BLF', '布洛芬缓释胶囊', '0.3g×20粒', '盒', 1, 12.50, 1, 1, '示例药业', 50, 1, NOW(), NOW()),
       (4002, 'YP-AMXL', '阿莫西林胶囊', '0.25g×24粒', '盒', 1, 9.80, 1, 1, '示例药业', 50, 1, NOW(), NOW()),
       (4003, 'YP-MTS', '蒙脱石散', '3g×10袋', '盒', 1, 15.60, 1, 1, '示例药业', 30, 1, NOW(), NOW()),
       (4004, 'YP-ABS', '盐酸氨溴索口服溶液', '100ml', '瓶', 1, 18.00, 1, 1, '示例药业', 30, 1, NOW(), NOW()),
       (4005, 'WC-DFMQ', '碘伏棉签', '10支', '袋', 2, 3.50, 0, 0, '示例医疗器械', 100, 1, NOW(), NOW());

INSERT INTO drug_batch (id, campus_id, drug_id, batch_no, expire_date, purchase_price, qty_on_hand, qty_locked,
                        create_time, update_time)
VALUES (5001, 1, 4001, 'B20250301', '2027-03-01', 8.20, 200, 0, NOW(), NOW()),
       (5002, 1, 4001, 'B20251101', '2027-11-01', 8.20, 300, 0, NOW(), NOW()),
       (5003, 1, 4002, 'A20250601', '2027-06-01', 6.10, 300, 0, NOW(), NOW()),
       (5004, 1, 4003, 'M20250801', '2027-08-01', 10.30, 200, 0, NOW(), NOW()),
       (5005, 1, 4004, 'S20250901', '2027-09-01', 12.00, 150, 0, NOW(), NOW()),
       (5006, 1, 4005, 'W20251001', '2028-10-01', 2.10, 500, 0, NOW(), NOW()),
       (5101, 2, 4001, 'B20250501', '2027-05-01', 8.20, 120, 0, NOW(), NOW()),
       (5102, 2, 4002, 'A20250701', '2027-07-01', 6.10, 120, 0, NOW(), NOW());

-- 期初入库流水，使“Σ流水 = 批次当前量”从第一天起成立
INSERT INTO stock_flow (id, batch_id, drug_id, flow_type, delta_on_hand, delta_locked, after_on_hand, after_locked,
                        biz_type, biz_no, operator_id, create_time)
SELECT id + 900000, id, drug_id, 1, qty_on_hand, 0, qty_on_hand, 0, 1, CONCAT('INIT', id), 0, NOW()
FROM drug_batch;
