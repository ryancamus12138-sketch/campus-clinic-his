package io.github.ryancamus.clinic.pharmacy.domain.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

@Schema(description = "药品目录维护")
public record DrugSaveDTO(
        @NotBlank @Pattern(regexp = "[A-Z0-9-]{2,32}") String drugCode,
        @Schema(description = "国家医保药品分类与代码") @Pattern(regexp = "[A-Z0-9]{8,32}") String insuranceCode,
        @NotBlank @Size(max = 100) String name,
        @NotBlank @Size(max = 64) String spec,
        @NotBlank @Size(max = 16) String unit,
        @Schema(description = "1 药品 2 卫生材料") @NotNull @Min(1) @Max(2) Integer goodsType,
        @NotNull @DecimalMin("0.00") BigDecimal retailPrice,
        @Schema(description = "0 不可报销 1 可报销") @NotNull @Min(0) @Max(1) Integer insuranceClass,
        @Schema(description = "发药是否必须扫追溯码") @NotNull @Min(0) @Max(1) Integer traceRequired,
        @Size(max = 100) String manufacturer,
        @Size(max = 32) String approvalNo,
        @NotNull @Min(0) Integer safetyStock,
        @Schema(description = "1 启用 0 停用") @NotNull @Min(0) @Max(1) Integer status) {
}
