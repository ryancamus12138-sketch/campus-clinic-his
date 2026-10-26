package io.github.ryancamus.clinic.patient.domain.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

@Schema(description = "患者建档")
public record PatientCreateDTO(
        @NotNull Long campusId,
        @NotBlank @Size(max = 50) String name,
        @Schema(description = "1 男 2 女；居民身份证会与号码核对") @NotNull @Min(1) @Max(2) Integer gender,
        @NotNull @Past LocalDate birthDate,
        @Schema(description = "1 学生 2 教职工 3 其他") @NotNull @Min(1) @Max(3) Integer patientType,
        @Schema(description = "学号/工号，学生和教职工必填") @Size(max = 20) String schoolNo,
        @Schema(description = "0 自费 1 大学生医保 2 职工医保 3 公费医疗") @NotNull @Min(0) @Max(3) Integer insuranceType,
        @Schema(description = "1 居民身份证 2 护照 3 港澳台居民居住证 4 港澳台通行证 5 外国人永久居留身份证")
        @NotNull @Min(1) @Max(5) Integer idType,
        @NotBlank @Size(max = 32) String idNo,
        @NotBlank @Size(max = 20) String phone) {
}
