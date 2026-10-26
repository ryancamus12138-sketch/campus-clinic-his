package io.github.ryancamus.clinic.system.controller;

import io.github.ryancamus.clinic.common.result.PageResult;
import io.github.ryancamus.clinic.common.result.R;
import io.github.ryancamus.clinic.system.domain.dto.UserCreateDTO;
import io.github.ryancamus.clinic.system.domain.dto.UserUpdateDTO;
import io.github.ryancamus.clinic.system.domain.query.UserQuery;
import io.github.ryancamus.clinic.system.domain.vo.RoleVO;
import io.github.ryancamus.clinic.system.domain.vo.UserSecretVO;
import io.github.ryancamus.clinic.system.domain.vo.UserVO;
import io.github.ryancamus.clinic.system.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "系统-用户")
@RestController
@RequestMapping("/api/system")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    @Operation(summary = "用户分页")
    @GetMapping("/users")
    @PreAuthorize("hasAuthority('system:user:list')")
    public R<PageResult<UserVO>> page(@Valid UserQuery query) {
        return R.ok(userService.page(query));
    }

    @Operation(summary = "可分配的角色")
    @GetMapping("/roles")
    @PreAuthorize("hasAuthority('system:user:list')")
    public R<List<RoleVO>> roles() {
        return R.ok(userService.roles());
    }

    @Operation(summary = "新增员工账号", description = "返回一次性的初始口令，首次登录必须修改")
    @PostMapping("/users")
    @PreAuthorize("hasAuthority('system:user:create')")
    public R<UserSecretVO> create(@Valid @RequestBody UserCreateDTO dto) {
        return R.ok(userService.create(dto));
    }

    @Operation(summary = "修改员工账号", description = "姓名、科室、角色、启用状态")
    @PutMapping("/users/{id}")
    @PreAuthorize("hasAuthority('system:user:update')")
    public R<Void> update(@PathVariable Long id, @Valid @RequestBody UserUpdateDTO dto) {
        userService.update(id, dto);
        return R.ok();
    }

    @Operation(summary = "重置口令", description = "生成一次性的临时口令，下次登录必须修改")
    @PostMapping("/users/{id}/reset-password")
    @PreAuthorize("hasAuthority('system:user:reset')")
    public R<UserSecretVO> resetPassword(@PathVariable Long id) {
        return R.ok(userService.resetPassword(id));
    }

    @Operation(summary = "解除登录锁定")
    @PostMapping("/users/{id}/unlock")
    @PreAuthorize("hasAuthority('system:user:reset')")
    public R<Void> unlock(@PathVariable Long id) {
        userService.unlock(id);
        return R.ok();
    }
}
