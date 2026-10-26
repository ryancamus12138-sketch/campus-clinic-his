package io.github.ryancamus.clinic.charge.domain.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Schema(description = "调价：到生效时间自动切换，之前已划价的收费单仍按原价")
public record PriceVersionDTO(
        @NotNull @DecimalMin("0.00") BigDecimal unitPrice,
        @NotNull @Min(0) @Max(1) Integer insuranceClass,
        @Schema(description = "生效时间，不早于现在；物价部门通知的执行日期") @NotNull LocalDateTime effectiveFrom) {
}
