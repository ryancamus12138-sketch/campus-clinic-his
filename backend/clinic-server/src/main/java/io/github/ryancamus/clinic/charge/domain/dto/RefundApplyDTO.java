package io.github.ryancamus.clinic.charge.domain.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

@Schema(description = "退费申请")
public record RefundApplyDTO(
        @NotEmpty @Size(max = 50) @Valid List<Line> items,
        @NotBlank @Size(max = 200) String reason) {

    public record Line(@NotNull Long chargeItemId, @NotNull @Min(1) @Max(999) Integer qty) {
    }
}
