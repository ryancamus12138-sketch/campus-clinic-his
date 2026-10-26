package io.github.ryancamus.clinic.pharmacy.domain.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.math.BigDecimal;
import java.time.LocalDate;

@Schema(description = "采购入库")
public record InboundDTO(
        @NotNull Long campusId,
        @NotNull Long drugId,
        @NotBlank @Pattern(regexp = "[A-Za-z0-9-]{1,32}") String batchNo,
        @NotNull LocalDate expireDate,
        @NotNull @DecimalMin("0.00") BigDecimal purchasePrice,
        @NotNull @Min(1) @Max(100_000) Integer qty) {
}
