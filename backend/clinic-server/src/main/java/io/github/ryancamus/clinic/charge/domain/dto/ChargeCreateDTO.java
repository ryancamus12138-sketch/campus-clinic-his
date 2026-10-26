package io.github.ryancamus.clinic.charge.domain.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

@Schema(description = "收费处划价")
public record ChargeCreateDTO(
        @NotNull Long patientId,
        @NotEmpty @Size(max = 50) @Valid List<ChargeItemDTO> items) {
}
