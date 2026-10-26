package io.github.ryancamus.clinic.system.domain.vo;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.Set;

@Schema(description = "当前登录用户；前端据 permissions 生成菜单与按钮")
public record CurrentUserVO(
        Long userId,
        String username,
        String nickname,
        @Schema(description = "1 员工") Integer userType,
        Long campusId,
        Set<String> roles,
        Set<String> permissions) {
}
