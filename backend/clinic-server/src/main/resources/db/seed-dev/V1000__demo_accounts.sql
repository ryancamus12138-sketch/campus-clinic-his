-- =====================================================================
-- 开发/演示数据（仅 dev、test 环境加载）
-- 员工账号密码 Clinic@2026，管理员密码 Admin@2026；患者为虚构数据。
-- =====================================================================

INSERT INTO patient (id, campus_id, patient_no, name, gender, birth_date, patient_type, school_no,
                     insurance_type, id_card_enc, id_card_hash, phone_enc, phone_hash, create_time, update_time)
VALUES (90001, 1, 'P2026000001', '演示学生', 1, '2004-05-01', 1, 'D20210001', 1,
        UNHEX('00'), SHA2('demo-idcard-90001', 256), UNHEX('00'), SHA2('demo-phone-90001', 256), NOW(), NOW()),
       (90002, 1, 'P2026000002', '演示教工', 2, '1985-09-10', 2, 'T19990001', 2,
        UNHEX('00'), SHA2('demo-idcard-90002', 256), UNHEX('00'), SHA2('demo-phone-90002', 256), NOW(), NOW());

INSERT INTO sys_user (id, username, password_hash, nickname, user_type, campus_id, dept_id, patient_id,
                      create_time, update_time)
VALUES (1, 'admin', '$2a$10$tphsMjiFLcLKYY4aIklF6e/qmrh6GWLIW1QlgefwiPXDsv1Uj0dYa', '系统管理员', 1, 1, NULL, NULL, NOW(), NOW()),
       (2, 'cashier01', '$2a$10$QaD8iT9moTLqYTurp6bUn.am6nYicd1GHaqvnyQ9VA5qCaN9FaAaq', '收费员小王', 1, 1, 107, NULL, NOW(), NOW()),
       (3, 'pharm01', '$2a$10$QaD8iT9moTLqYTurp6bUn.am6nYicd1GHaqvnyQ9VA5qCaN9FaAaq', '药师小李', 1, 1, 106, NULL, NOW(), NOW()),
       (4, 'doctor01', '$2a$10$QaD8iT9moTLqYTurp6bUn.am6nYicd1GHaqvnyQ9VA5qCaN9FaAaq', '内科张医生', 1, 1, 101, NULL, NOW(), NOW()),
       (5, 'finance01', '$2a$10$QaD8iT9moTLqYTurp6bUn.am6nYicd1GHaqvnyQ9VA5qCaN9FaAaq', '财务小陈', 1, 1, NULL, NULL, NOW(), NOW()),
       (6, 'D20210001', '$2a$10$QaD8iT9moTLqYTurp6bUn.am6nYicd1GHaqvnyQ9VA5qCaN9FaAaq', '演示学生', 2, 1, NULL, 90001, NOW(), NOW()),
       (7, 'T19990001', '$2a$10$QaD8iT9moTLqYTurp6bUn.am6nYicd1GHaqvnyQ9VA5qCaN9FaAaq', '演示教工', 2, 1, NULL, 90002, NOW(), NOW());

INSERT INTO sys_user_role (user_id, role_id)
VALUES (1, 1), (2, 2), (3, 3), (4, 4), (5, 5), (6, 6), (7, 6);
