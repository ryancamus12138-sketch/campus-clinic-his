package io.github.ryancamus.clinic.system.domain.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

@Schema(description = "修改收费点")
public record ChargePointDTO(
        @NotBlank @Size(max = 50) String name,
        @NotEmpty List<@Pattern(regexp = "CASH|BANK_CARD|CAMPUS_CARD|AUTH_CODE") String> counterMethods,
        @NotNull @Schema(description = "1 启用 0 停用") Integer status) {
}
