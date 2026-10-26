package io.github.ryancamus.clinic.patient.domain.vo;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDate;

@Schema(description = "患者档案（证件号、手机号为脱敏值）")
public record PatientDetailVO(
        Long id,
        String patientNo,
        String name,
        Integer gender,
        LocalDate birthDate,
        Integer patientType,
        String schoolNo,
        Integer insuranceType,
        Integer idType,
        @Schema(description = "脱敏证件号，如 3*************1234") String idNoMasked,
        @Schema(description = "脱敏手机号，如 138****5678") String phoneMasked,
        Long campusId) {
}
