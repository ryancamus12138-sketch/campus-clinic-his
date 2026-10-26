package io.github.ryancamus.clinic.clinic.domain.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

@Schema(description = "开具处方")
public record PrescriptionCreateDTO(
        @NotNull Long registrationId,
        @NotEmpty @Size(max = 30) @Valid List<Line> items) {

    public record Line(
            @NotNull @Schema(description = "1 药品/卫生材料 2 诊疗项目") Integer kind,
            Long drugId,
            String itemCode,
            @NotNull @Min(1) @Max(99) Integer qty,
            @Size(max = 100) @Schema(description = "用法用量，如 每日 2 次，每次 1 粒") String usageNote) {
    }
}
