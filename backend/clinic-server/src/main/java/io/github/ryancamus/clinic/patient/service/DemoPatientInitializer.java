package io.github.ryancamus.clinic.patient.service;

import io.github.ryancamus.clinic.framework.crypto.FieldCipher;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 开发与测试环境：演示患者由 SQL 种子插入，SQL 里无法使用应用的加密密钥，
 * 启动时用当前环境的密钥补上证件号、手机号密文和盲索引。号码为虚构（地区码 999999 不存在）。
 */
@Component
@Profile({"dev", "test"})
@RequiredArgsConstructor
public class DemoPatientInitializer implements ApplicationRunner {

    private static final Object[][] DEMO = {
            {90001L, "999999200405010018", "13900000001"},
            {90002L, "999999198509100026", "13900000002"},
    };

    private final JdbcTemplate jdbc;
    private final FieldCipher cipher;

    @Override
    public void run(ApplicationArguments args) {
        for (Object[] d : DEMO) {
            String idNo = (String) d[1];
            String phone = (String) d[2];
            jdbc.update("UPDATE patient SET id_card_enc = ?, id_card_hash = ?, phone_enc = ?, phone_hash = ? "
                            + "WHERE id = ? AND id_card_enc = UNHEX('00')",
                    cipher.encrypt(PatientService.PURPOSE_ID_NO, idNo),
                    cipher.blindIndex(PatientService.PURPOSE_ID_NO, idNo),
                    cipher.encrypt(PatientService.PURPOSE_PHONE, phone),
                    cipher.blindIndex(PatientService.PURPOSE_PHONE, phone), d[0]);
        }
    }
}
