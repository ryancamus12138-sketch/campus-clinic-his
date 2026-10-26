package io.github.ryancamus.clinic.schedule.domain.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

@Schema(description = "修改号源状态")
public record SlotStatusDTO(@Schema(description = "0 未开放 1 开放 2 停诊") @NotNull @Min(0) @Max(2) Integer status) {
}
