package io.github.ryancamus.clinic.patient.api;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;

/**
 * 患者模块对外提供的档案查询。收费需要患者的院区、身份与参保类型来确定计费策略。
 */
public interface PatientQueryApi {

    Optional<PatientSnapshot> findById(Long patientId);

    /** 批量取姓名，用于列表展示 */
    Map<Long, String> names(Collection<Long> patientIds);

    /**
     * @param patientType   1 学生 2 教职工 3 其他
     * @param insuranceType 0 自费 1 大学生医保 2 职工医保 3 公费医疗
     */
    record PatientSnapshot(Long id, Long campusId, String patientNo, String name, int patientType,
                           int insuranceType) {
    }
}
