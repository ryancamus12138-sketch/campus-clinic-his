package io.github.ryancamus.clinic.system.domain.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

public record UserUpdateDTO(
        @NotBlank @Size(max = 50) String nickname,
        @NotNull Long campusId,
        Long deptId,
        @NotEmpty List<Long> roleIds,
        @NotNull @Min(0) @Max(1) Integer status) {
}
