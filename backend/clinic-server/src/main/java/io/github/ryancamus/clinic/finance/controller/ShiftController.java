package io.github.ryancamus.clinic.finance.controller;

import io.github.ryancamus.clinic.common.result.PageResult;
import io.github.ryancamus.clinic.common.result.R;
import io.github.ryancamus.clinic.finance.domain.dto.HandOverDTO;
import io.github.ryancamus.clinic.finance.domain.dto.ReviewDTO;
import io.github.ryancamus.clinic.finance.domain.vo.ShiftVO;
import io.github.ryancamus.clinic.finance.service.ShiftService;
import io.github.ryancamus.clinic.framework.security.LoginUser;
import io.github.ryancamus.clinic.framework.security.SecurityUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

@Tag(name = "财务-日结交班")
@Validated
@RestController
@RequestMapping("/api/finance/shifts")
@RequiredArgsConstructor
public class ShiftController {

    private final ShiftService shiftService;

    @Operation(summary = "我的当前班次", description = "当班第一笔窗口收费时自动开班；未开班返回空")
    @GetMapping("/current")
    @PreAuthorize("hasAuthority('finance:shift')")
    public R<ShiftVO> current() {
        return R.ok(shiftService.current(SecurityUtils.requireUser().getUserId()).orElse(null));
    }

    @Operation(summary = "交班", description = "金额由本班资金流水汇总；收费员录入实点现金，有银行卡业务的录入 POS 机结算单金额，"
            + "任一不一致须说明")
    @PostMapping("/{id}/hand-over")
    @PreAuthorize("hasAuthority('finance:shift')")
    public R<ShiftVO> handOver(@PathVariable Long id, @Valid @RequestBody HandOverDTO dto) {
        return R.ok(shiftService.handOver(id, SecurityUtils.requireUser().getUserId(), dto.countedCash(),
                dto.posCounted(), dto.diffNote()));
    }

    @Operation(summary = "财务复核", description = "收费员本人不能复核自己的班次")
    @PostMapping("/{id}/review")
    @PreAuthorize("hasAuthority('finance:report')")
    public R<ShiftVO> review(@PathVariable Long id, @Valid @RequestBody(required = false) ReviewDTO dto) {
        return R.ok(shiftService.review(id, SecurityUtils.requireUser().getUserId(), dto == null ? null : dto.note()));
    }

    @Operation(summary = "班次列表", description = "收费员只能看自己的，财务看全部")
    @GetMapping
    @PreAuthorize("hasAuthority('finance:shift')")
    public R<PageResult<ShiftVO>> page(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(defaultValue = "1") @Min(1) long page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(200) long size) {
        LoginUser user = SecurityUtils.requireUser();
        Long cashierId = user.getPermissions().contains("finance:report") ? null : user.getUserId();
        return R.ok(shiftService.page(date, cashierId, page, size));
    }
}
