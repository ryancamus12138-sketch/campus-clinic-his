package io.github.ryancamus.clinic.system.domain.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 登录时口令到期（或管理员重置后首次登录）修改口令 */
public record ExpiredPasswordDTO(@NotBlank @Size(max = 64) String challenge,
                                 @NotBlank @Size(max = 64) String newPassword) {
}
