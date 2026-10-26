package io.github.ryancamus.clinic.system.domain.vo;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "下拉选项")
public record OptionVO(Long id, String name, @Schema(description = "附加信息，如所属院区、科室") Long groupId) {
}
