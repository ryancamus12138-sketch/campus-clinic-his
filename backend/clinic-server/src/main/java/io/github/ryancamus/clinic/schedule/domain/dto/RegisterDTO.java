package io.github.ryancamus.clinic.schedule.domain.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

@Schema(description = "窗口挂号：收费员为患者选择号源")
public record RegisterDTO(@NotNull Long patientId, @NotNull Long slotId) {
}
