package io.github.ryancamus.clinic.system.domain.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Schema(description = "修改校区")
public record CampusDTO(@NotBlank @Size(max = 50) String name, @Size(max = 200) String address) {
}
