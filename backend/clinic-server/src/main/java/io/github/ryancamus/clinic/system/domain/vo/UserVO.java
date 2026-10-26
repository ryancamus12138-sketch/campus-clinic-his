package io.github.ryancamus.clinic.system.domain.vo;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;
import java.util.List;

@Schema(description = "用户列表项（不含密码等敏感字段）")
public record UserVO(
        Long id,
        String username,
        String nickname,
        @Schema(description = "1 员工") Integer userType,
        Long campusId,
        Long deptId,
        @Schema(description = "1 启用 0 停用") Integer status,
        LocalDateTime lastLoginTime,
        LocalDateTime createTime,
        @Schema(description = "1 下次登录必须修改口令") Integer mustChangePassword,
        @Schema(description = "角色") List<Long> roleIds) {

    public UserVO withRoleIds(List<Long> ids) {
        return new UserVO(id, username, nickname, userType, campusId, deptId, status, lastLoginTime, createTime,
                mustChangePassword, ids);
    }
}
