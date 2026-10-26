package io.github.ryancamus.clinic.system.domain.vo;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(description = "登录页信息")
public record AuthOptionsVO(
        @Schema(description = "机构名称（clinic.org.name）") String orgName,
        @Schema(description = "可选择的登录身份：系统中启用的角色") List<LoginRole> roles) {

    public record LoginRole(@Schema(description = "角色编码") String code, @Schema(description = "角色名称") String name) {
    }
}
