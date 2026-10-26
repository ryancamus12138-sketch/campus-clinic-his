package io.github.ryancamus.clinic.charge.controller;

import io.github.ryancamus.clinic.charge.domain.dto.RefundApplyDTO;
import io.github.ryancamus.clinic.charge.domain.dto.RefundConfirmDTO;
import io.github.ryancamus.clinic.charge.domain.dto.RefundDecisionDTO;
import io.github.ryancamus.clinic.charge.domain.dto.RefundInsuranceDTO;
import io.github.ryancamus.clinic.charge.domain.vo.RefundVO;
import io.github.ryancamus.clinic.charge.service.RefundService;
import io.github.ryancamus.clinic.common.result.PageResult;
import io.github.ryancamus.clinic.common.result.R;
import io.github.ryancamus.clinic.framework.security.SecurityUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "收费-退费")
@Validated
@RestController
@RequestMapping("/api/charge")
@RequiredArgsConstructor
public class RefundController {

    private final RefundService refundService;

    @Operation(summary = "申请退费", description = "按明细和数量退；已发药的需先到药房退药。请求头 Idempotency-Key 防重复提交")
    @PostMapping("/orders/{id}/refunds")
    @PreAuthorize("hasAuthority('charge:refund:apply')")
    public R<RefundVO> apply(@PathVariable Long id, @Valid @RequestBody RefundApplyDTO dto,
                             @RequestHeader("Idempotency-Key") String idempotencyKey) {
        return R.ok(refundService.apply(id, dto, idempotencyKey, SecurityUtils.requireUser().getUserId()));
    }

    @Operation(summary = "退费单列表", description = "status：0 待审核 1 退款中（含待收费员确认）2 成功 3 失败 4 已驳回")
    @GetMapping("/refunds")
    @PreAuthorize("hasAnyAuthority('charge:refund:approve', 'charge:order:list')")
    public R<PageResult<RefundVO>> page(@RequestParam(required = false) Integer status,
                                        @RequestParam(defaultValue = "1") @Min(1) long page,
                                        @RequestParam(defaultValue = "20") @Min(1) @Max(200) long size) {
        return R.ok(refundService.page(status, page, size));
    }

    @Operation(summary = "审批通过并执行退款", description = "可附审批意见；申请人本人不能审批")
    @PostMapping("/refunds/{id}/approve")
    @PreAuthorize("hasAuthority('charge:refund:approve')")
    public R<RefundVO> approve(@PathVariable Long id, @Valid @RequestBody(required = false) RefundDecisionDTO dto) {
        return R.ok(refundService.approve(id, SecurityUtils.requireUser().getUserId(), dto == null ? null : dto.note()));
    }

    @Operation(summary = "收费员确认已退还", description = "现金当面退还、银行卡在 POS 机上退货后确认；记录确认人、时间和班次")
    @PostMapping("/refunds/{id}/tenders/{tenderId}/confirm")
    @PreAuthorize("hasAuthority('charge:refund:apply')")
    public R<RefundVO> confirmTender(@PathVariable Long id, @PathVariable Long tenderId,
                                     @Valid @RequestBody RefundConfirmDTO dto) {
        return R.ok(refundService.confirmTender(id, tenderId, dto.reference(), dto.note(),
                SecurityUtils.requireUser().getUserId()));
    }

    @Operation(summary = "登记医保终端的撤销与重结", description = "医保平台未接入时：在医保结算终端撤销原结算、按剩余项目重新结算后登记；"
            + "退费后没有医保报销部分时请求体为空对象")
    @PostMapping("/refunds/{id}/insurance-registration")
    @PreAuthorize("hasAuthority('charge:refund:apply')")
    public R<RefundVO> registerInsurance(@PathVariable Long id, @Valid @RequestBody RefundInsuranceDTO dto) {
        return R.ok(refundService.registerInsurance(id, dto.resettlement(), SecurityUtils.requireUser().getUserId()));
    }

    @Operation(summary = "驳回", description = "必须写明理由；归还预占的退费数量。申请人本人不能驳回")
    @PostMapping("/refunds/{id}/reject")
    @PreAuthorize("hasAuthority('charge:refund:approve')")
    public R<Void> reject(@PathVariable Long id, @Valid @RequestBody RefundDecisionDTO dto) {
        refundService.reject(id, SecurityUtils.requireUser().getUserId(), dto.note());
        return R.ok();
    }
}
