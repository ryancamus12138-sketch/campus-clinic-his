package io.github.ryancamus.clinic.pharmacy.domain.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

@Schema(description = "发药")
public record DispenseDTO(
        @NotBlank String chargeNo,
        @NotEmpty @Size(max = 50) @Valid List<Line> items) {

    public record Line(
            @NotNull Long chargeItemId,
            @NotNull @Min(1) @Max(999) Integer qty,
            @Schema(description = "需要扫码的药品逐盒扫描，数量须等于 qty") List<String> traceCodes) {
    }
}
