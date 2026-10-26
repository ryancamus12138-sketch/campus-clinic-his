package io.github.ryancamus.clinic.charge.controller;

import io.github.ryancamus.clinic.charge.domain.dto.CheckoutResolveDTO;
import io.github.ryancamus.clinic.charge.domain.dto.TenderVerifyDTO;
import io.github.ryancamus.clinic.charge.domain.vo.CheckoutExceptionVO;
import io.github.ryancamus.clinic.charge.service.CounterCheckoutService;
import io.github.ryancamus.clinic.common.result.R;
import io.github.ryancamus.clinic.framework.security.SecurityUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "收费-窗口结算异常（财务）")
@RestController
@RequestMapping("/api/charge/checkouts")
@RequiredArgsConstructor
public class CounterCheckoutController {

    private final CounterCheckoutService counterCheckoutService;

    @Operation(summary = "收款异常", description = "撤回失败、医保撤销失败或中途中断的结算。resolved=true 查看已处理记录")
    @GetMapping("/exceptions")
    @PreAuthorize("hasAuthority('charge:exception:handle')")
    public R<List<CheckoutExceptionVO>> exceptions(@RequestParam(defaultValue = "false") boolean resolved) {
        return R.ok(counterCheckoutService.exceptions(resolved));
    }

    @Operation(summary = "重试撤销医保结算", description = "返回是否已全部撤销")
    @PostMapping("/{id}/retry-insurance-cancel")
    @PreAuthorize("hasAuthority('charge:exception:handle')")
    public R<Boolean> retryInsuranceCancel(@PathVariable Long id) {
        return R.ok(counterCheckoutService.retryInsuranceCancel(id));
    }

    @Operation(summary = "逐笔核实一笔收款", description = "可能已扣款的每笔收款核实为“已退还患者”或“实际未扣款”，"
            + "非现金须填写凭证号；记录核实人和时间。当笔收费的收费员本人不能核实")
    @PostMapping("/{id}/tenders/{tenderId}/verify")
    @PreAuthorize("hasAuthority('charge:exception:handle')")
    public R<Void> verifyTender(@PathVariable Long id, @PathVariable Long tenderId,
                                @Valid @RequestBody TenderVerifyDTO dto) {
        counterCheckoutService.verifyTender(id, tenderId, dto, SecurityUtils.requireUser().getUserId());
        return R.ok();
    }

    @Operation(summary = "标记已处理", description = "可能已扣款的收款须已逐笔核实；医保结算须已撤销或确认已在医保端手工撤销；"
            + "记录处理人、时间和说明。当笔收费的收费员本人不能处理")
    @PostMapping("/{id}/resolve")
    @PreAuthorize("hasAuthority('charge:exception:handle')")
    public R<Void> resolve(@PathVariable Long id, @Valid @RequestBody CheckoutResolveDTO dto) {
        counterCheckoutService.resolve(id, dto.note(), dto.insuranceCancelledManually(),
                SecurityUtils.requireUser().getUserId());
        return R.ok();
    }
}
