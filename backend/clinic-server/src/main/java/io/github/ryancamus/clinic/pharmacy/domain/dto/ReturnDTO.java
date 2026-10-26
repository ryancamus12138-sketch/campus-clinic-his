package io.github.ryancamus.clinic.pharmacy.domain.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.List;

@Schema(description = "退药")
public record ReturnDTO(
        @NotBlank String chargeNo,
        @NotNull Long chargeItemId,
        @NotNull @Min(1) @Max(999) Integer qty,
        @Schema(description = "需要扫码的药品扫描退回的每一盒") List<String> traceCodes) {
}
