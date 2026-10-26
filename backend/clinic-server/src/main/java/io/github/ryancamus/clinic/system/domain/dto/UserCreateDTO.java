package io.github.ryancamus.clinic.system.domain.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

@Schema(description = "新增员工账号；初始口令由系统生成，只返回一次，首次登录必须修改")
public record UserCreateDTO(
        @NotBlank @Pattern(regexp = "[A-Za-z][A-Za-z0-9_]{3,31}") String username,
        @NotBlank @Size(max = 50) String nickname,
        @NotNull Long campusId,
        Long deptId,
        @NotEmpty List<Long> roleIds) {
}
