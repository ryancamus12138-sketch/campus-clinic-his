package io.github.ryancamus.clinic.system.domain.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Schema(description = "刷新令牌请求；退出登录时可选携带，用于同时作废刷新令牌")
public record RefreshTokenDTO(@NotBlank @Size(max = 128) String refreshToken) {
}
