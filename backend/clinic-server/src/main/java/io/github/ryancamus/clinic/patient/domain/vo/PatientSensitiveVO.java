package io.github.ryancamus.clinic.patient.domain.vo;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "完整证件号与手机号（每次查看都记审计日志）")
public record PatientSensitiveVO(Long id, Integer idType, String idNo, String phone) {
}
