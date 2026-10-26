package io.github.ryancamus.clinic.charge.domain.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

@Schema(description = "划价明细")
public record ChargeItemDTO(
        @NotBlank @Schema(description = "价目编码", example = "JC-XCG") String itemCode,
        @NotNull @Min(1) @Max(999) Integer qty) {
}
