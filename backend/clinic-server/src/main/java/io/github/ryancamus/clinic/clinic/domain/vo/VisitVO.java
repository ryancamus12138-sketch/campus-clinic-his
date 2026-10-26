package io.github.ryancamus.clinic.clinic.domain.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDate;
import java.time.LocalTime;

@Schema(description = "候诊记录")
public record VisitVO(
        Long registrationId,
        String regNo,
        Long patientId,
        String patientName,
        @Schema(description = "1 候诊 2 已就诊") Integer status,
        LocalDate slotDate,
        @JsonFormat(pattern = "HH:mm") LocalTime startTime) {
}
