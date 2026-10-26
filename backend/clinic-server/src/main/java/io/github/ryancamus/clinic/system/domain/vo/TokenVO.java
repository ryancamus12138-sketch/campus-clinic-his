package io.github.ryancamus.clinic.system.domain.vo;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "令牌")
public record TokenVO(
        @Schema(description = "访问令牌，放在 Authorization: Bearer 头中") String accessToken,
        @Schema(description = "刷新令牌，一次性使用") String refreshToken,
        @Schema(description = "访问令牌剩余有效秒数") long expiresIn,
        String tokenType) {

    public static TokenVO bearer(String accessToken, String refreshToken, long expiresIn) {
        return new TokenVO(accessToken, refreshToken, expiresIn, "Bearer");
    }
}
