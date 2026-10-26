package io.github.ryancamus.clinic.system.domain.query;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

@Schema(description = "用户分页查询条件")
public record UserQuery(
        @Schema(description = "页码，从 1 开始") @Min(1) Integer page,
        @Schema(description = "每页条数，最大 100") @Min(1) @Max(100) Integer size,
        @Schema(description = "用户名或昵称关键字") @Size(max = 50) String keyword,
        @Schema(description = "1 员工") Integer userType) {

    public long pageOrDefault() {
        return page == null ? 1 : page;
    }

    public long sizeOrDefault() {
        return size == null ? 20 : size;
    }
}
