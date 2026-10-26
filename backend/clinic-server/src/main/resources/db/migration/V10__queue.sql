-- P8c：到院签到与分诊叫号

CREATE TABLE queue_ticket (
    id              BIGINT      NOT NULL PRIMARY KEY,
    campus_id       BIGINT      NOT NULL,
    dept_id         BIGINT      NOT NULL,
    queue_date      DATE        NOT NULL,
    seq             INT         NOT NULL COMMENT '当天本科室的排队序号，显示为 3 位数字',
    registration_id BIGINT      NOT NULL,
    patient_id      BIGINT      NOT NULL,
    status          TINYINT     NOT NULL COMMENT '0候诊 1已叫号 2已完成 3过号 4已取消',
    check_in_time   DATETIME(3) NOT NULL COMMENT '排队先后以签到时间为准；过号后重新签到排到队尾',
    doctor_id       BIGINT      NULL,
    room            VARCHAR(20) NULL COMMENT '诊室',
    call_time       DATETIME(3) NULL,
    call_count      INT         NOT NULL DEFAULT 0,
    finish_time     DATETIME    NULL,
    create_time     DATETIME    NOT NULL,
    update_time     DATETIME    NOT NULL,
    create_by       BIGINT      NOT NULL DEFAULT 0,
    update_by       BIGINT      NOT NULL DEFAULT 0,
    version         INT         NOT NULL DEFAULT 0,
    UNIQUE KEY uk_registration (registration_id),
    UNIQUE KEY uk_dept_seq (dept_id, queue_date, seq),
    KEY idx_waiting (campus_id, dept_id, queue_date, status, check_in_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='排队叫号';

INSERT INTO sys_permission (id, parent_id, perm_code, perm_name, perm_type, path, sort, create_time, update_time)
VALUES (710, 700, 'queue:call', '叫号', 2, NULL, 4, NOW(), NOW()),
       (711, 700, 'queue:manage', '签到与排队管理', 1, '/clinic/queue', 5, NOW(), NOW());

-- 医生叫号；收费窗口（兼分诊台）代患者签到、处理过号
INSERT INTO sys_role_permission (role_id, permission_id)
VALUES (1, 710), (1, 711),
       (4, 710),
       (2, 700), (2, 711);
