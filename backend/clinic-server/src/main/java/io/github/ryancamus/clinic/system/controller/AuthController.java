package io.github.ryancamus.clinic.system.controller;

import io.github.ryancamus.clinic.common.result.R;
import io.github.ryancamus.clinic.system.domain.dto.ExpiredPasswordDTO;
import io.github.ryancamus.clinic.system.domain.dto.LoginDTO;
import io.github.ryancamus.clinic.system.domain.dto.PasswordChangeDTO;
import io.github.ryancamus.clinic.system.domain.dto.RefreshTokenDTO;
import io.github.ryancamus.clinic.system.domain.vo.AuthOptionsVO;
import io.github.ryancamus.clinic.system.domain.vo.CurrentUserVO;
import io.github.ryancamus.clinic.system.domain.vo.LoginResultVO;
import io.github.ryancamus.clinic.system.domain.vo.TokenVO;
import io.github.ryancamus.clinic.system.service.AuthService;
import io.github.ryancamus.clinic.system.service.OrgProperties;
import io.github.ryancamus.clinic.system.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "认证")
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final UserService userService;
    private final OrgProperties orgProperties;

    @Operation(summary = "登录", description = "连续失败达到次数后锁定一段时间（clinic.security.max-login-failures、lock-duration，默认 5 次、15 分钟）。传入 role 时核对账号是否具备该身份，不具备返回 10022。"
            + "临时口令（新开账号、管理员重置）返回下一步（step）而不是令牌")
    @PostMapping("/login")
    public R<LoginResultVO> login(@Valid @RequestBody LoginDTO dto) {
        return R.ok(authService.login(dto));
    }

    @Operation(summary = "首次登录修改临时口令", description = "step=PASSWORD_CHANGE 时调用")
    @PostMapping("/password/expired")
    public R<LoginResultVO> changeExpiredPassword(@Valid @RequestBody ExpiredPasswordDTO dto) {
        return R.ok(authService.changeExpiredPassword(dto.challenge(), dto.newPassword()));
    }

    @Operation(summary = "修改口令")
    @PostMapping("/password")
    public R<Void> changePassword(@Valid @RequestBody PasswordChangeDTO dto) {
        authService.changePassword(dto.oldPassword(), dto.newPassword());
        return R.ok();
    }

    @Operation(summary = "刷新令牌", description = "刷新令牌一次性使用，返回新的令牌对")
    @PostMapping("/refresh")
    public R<TokenVO> refresh(@Valid @RequestBody RefreshTokenDTO dto) {
        return R.ok(authService.refresh(dto.refreshToken()));
    }

    @Operation(summary = "退出登录", description = "当前访问令牌加入黑名单，并作废传入的刷新令牌")
    @PostMapping("/logout")
    public R<Void> logout(@RequestBody(required = false) RefreshTokenDTO dto) {
        authService.logout(dto == null ? null : dto.refreshToken());
        return R.ok();
    }

    @Operation(summary = "当前用户", description = "返回角色和权限码，前端据此生成菜单")
    @GetMapping("/me")
    public R<CurrentUserVO> me() {
        return R.ok(authService.currentUser());
    }

    @Operation(summary = "登录页信息", description = "机构名称；可选择的登录身份（系统中启用的角色）")
    @GetMapping("/options")
    public R<AuthOptionsVO> options() {
        return R.ok(new AuthOptionsVO(orgProperties.name(), userService.roles().stream()
                .map(r -> new AuthOptionsVO.LoginRole(r.code(), r.name())).toList()));
    }
}
