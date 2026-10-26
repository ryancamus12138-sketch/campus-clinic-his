package io.github.ryancamus.clinic.finance.controller;

import io.github.ryancamus.clinic.common.result.PageResult;
import io.github.ryancamus.clinic.common.result.R;
import io.github.ryancamus.clinic.finance.domain.vo.ReconDiffVO;
import io.github.ryancamus.clinic.finance.domain.vo.ReconResultVO;
import io.github.ryancamus.clinic.finance.service.ReconService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

@Tag(name = "财务-渠道对账")
@Validated
@RestController
@RequestMapping("/api/finance/recon")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('finance:recon')")
public class ReconController {

    private final ReconService reconService;

    @Operation(summary = "执行对账", description = "每天凌晨自动对前一天；可手动重跑，差异不会重复登记")
    @PostMapping("/run")
    public R<ReconResultVO> run(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return R.ok(reconService.reconcile(date));
    }

    @Operation(summary = "差异列表", description = "status：0 待处理 1 已处理")
    @GetMapping("/diffs")
    public R<PageResult<ReconDiffVO>> diffs(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(required = false) Integer status,
            @RequestParam(defaultValue = "1") @Min(1) long page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(200) long size) {
        return R.ok(reconService.diffs(date, status, page, size));
    }

    @Operation(summary = "标记已处理")
    @PostMapping("/diffs/{id}/resolve")
    public R<Void> resolve(@PathVariable Long id, @RequestParam @NotBlank @Size(max = 200) String remark) {
        reconService.resolve(id, remark);
        return R.ok();
    }
}
