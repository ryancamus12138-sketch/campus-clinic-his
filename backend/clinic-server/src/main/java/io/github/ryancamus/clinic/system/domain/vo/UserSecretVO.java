package io.github.ryancamus.clinic.system.domain.vo;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "新建账号或重置口令的结果：临时口令只在本次返回，首次登录必须修改")
public record UserSecretVO(Long id, String username, String temporaryPassword) {
}
