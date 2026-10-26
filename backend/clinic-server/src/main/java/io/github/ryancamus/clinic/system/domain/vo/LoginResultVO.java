package io.github.ryancamus.clinic.system.domain.vo;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 登录结果。口令校验通过后，临时口令（新开账号或被管理员重置）还需要先修改：
 * step 为空表示登录完成，令牌字段有值；否则令牌为空，凭 challenge 调用修改口令接口继续。
 */
@Schema(description = "登录结果")
public record LoginResultVO(
        String accessToken,
        String refreshToken,
        Long expiresIn,
        String tokenType,
        @Schema(description = "下一步：PASSWORD_CHANGE 修改临时口令；为空表示完成") String step,
        @Schema(description = "继续登录的凭证，5 分钟内有效，只能使用一次") String challenge) {

    public static final String STEP_PASSWORD_CHANGE = "PASSWORD_CHANGE";

    public static LoginResultVO done(TokenVO tokens) {
        return new LoginResultVO(tokens.accessToken(), tokens.refreshToken(), tokens.expiresIn(), tokens.tokenType(),
                null, null);
    }

    public static LoginResultVO next(String step, String challenge) {
        return new LoginResultVO(null, null, null, null, step, challenge);
    }
}
