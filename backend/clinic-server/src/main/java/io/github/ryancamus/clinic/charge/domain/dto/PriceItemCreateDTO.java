package io.github.ryancamus.clinic.charge.domain.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

@Schema(description = "新增收费项目")
public record PriceItemCreateDTO(
        @NotBlank @Pattern(regexp = "[A-Z0-9-]{2,32}") String itemCode,
        @NotBlank @Size(max = 100) String itemName,
        @Schema(description = "医疗服务项目编码") @Pattern(regexp = "[A-Za-z0-9.-]{4,32}") String insuranceCode,
        @Schema(description = "1 挂号 3 诊疗 4 检查 6 体检 7 疫苗服务") @NotNull Integer itemType,
        @NotBlank @Size(max = 16) String unit,
        @NotNull @DecimalMin("0.00") BigDecimal unitPrice,
        @Schema(description = "0 不可报销 1 可报销") @NotNull @Min(0) @Max(1) Integer insuranceClass) {
}
