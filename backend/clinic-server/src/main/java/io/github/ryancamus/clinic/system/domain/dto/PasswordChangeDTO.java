package io.github.ryancamus.clinic.system.domain.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record PasswordChangeDTO(@NotBlank @Size(max = 64) String oldPassword,
                                @NotBlank @Size(max = 64) String newPassword) {
}
