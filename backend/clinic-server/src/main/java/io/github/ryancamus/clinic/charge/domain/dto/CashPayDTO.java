package io.github.ryancamus.clinic.charge.domain.dto;

import io.github.ryancamus.clinic.common.money.Money;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

@Schema(description = "现金收费")
public record CashPayDTO(@NotNull @Schema(description = "实收金额", example = "20.00") Money received) {
}
