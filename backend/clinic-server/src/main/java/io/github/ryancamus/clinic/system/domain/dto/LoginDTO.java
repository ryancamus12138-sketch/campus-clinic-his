package io.github.ryancamus.clinic.system.domain.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * @param role 登录页选择的身份（角色编码，如 CASHIER）。只用于核对账号是否具备该身份：不具备则拒绝登录；
 *             权限始终取自账号在数据库中的角色，不会因为这里传了什么而增加。不传则不核对。
 */
@Schema(description = "登录请求")
public record LoginDTO(
        @Schema(description = "用户名", example = "cashier01")
        @NotBlank @Size(max = 50) String username,
        @Schema(description = "口令")
        @NotBlank @Size(max = 64) String password,
        @Schema(description = "登录页选择的身份（角色编码）；账号不具备该身份时拒绝登录", example = "CASHIER")
        @Pattern(regexp = "[A-Z_]{2,32}") String role) {

    public LoginDTO(String username, String password) {
        this(username, password, null);
    }
}
