-- 演示审计管理员，密码 Clinic@2026
INSERT INTO sys_user (id, username, password_hash, nickname, user_type, campus_id, dept_id, patient_id,
                      create_time, update_time)
VALUES (8, 'auditor01', '$2a$10$QaD8iT9moTLqYTurp6bUn.am6nYicd1GHaqvnyQ9VA5qCaN9FaAaq', '审计员小周', 1, 1, NULL, NULL,
        NOW(), NOW());

INSERT INTO sys_user_role (user_id, role_id) VALUES (8, 7);
