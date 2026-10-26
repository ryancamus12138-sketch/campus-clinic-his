package io.github.ryancamus.clinic.finance.domain.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;

@Schema(description = "财务复核意见")
public record ReviewDTO(@Size(max = 200) @Schema(description = "如：短款 2 元已由收费员补齐") String note) {
}
