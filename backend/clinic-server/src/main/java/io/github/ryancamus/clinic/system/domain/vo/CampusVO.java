package io.github.ryancamus.clinic.system.domain.vo;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "校区")
public record CampusVO(Long id, String name, String address) {
}
