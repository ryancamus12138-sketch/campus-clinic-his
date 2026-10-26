package io.github.ryancamus.clinic.patient.domain.vo;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "患者摘要（收费处检索用）")
public record PatientBriefVO(
        Long id,
        String patientNo,
        String name,
        @Schema(description = "学号/工号") String schoolNo,
        @Schema(description = "1 学生 2 教职工 3 其他") Integer patientType,
        @Schema(description = "0 自费 1 大学生医保 2 职工医保 3 公费医疗") Integer insuranceType,
        Long campusId) {
}
