package io.github.ryancamus.clinic.finance.controller;

import io.github.ryancamus.clinic.common.result.PageResult;
import io.github.ryancamus.clinic.common.result.R;
import io.github.ryancamus.clinic.finance.domain.dto.ManualInvoiceDTO;
import io.github.ryancamus.clinic.finance.domain.vo.InvoiceVO;
import io.github.ryancamus.clinic.finance.service.InvoiceService;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "财务-电子票据")
@Validated
@RestController
@RequiredArgsConstructor
public class InvoiceController {

    private final InvoiceService invoiceService;

    @Operation(summary = "票据查询")
    @GetMapping("/api/finance/invoices")
    @PreAuthorize("hasAuthority('finance:invoice')")
    public R<PageResult<InvoiceVO>> page(@RequestParam(required = false) String chargeNo,
                                         @RequestParam(defaultValue = "1") @Min(1) long page,
                                         @RequestParam(defaultValue = "20") @Min(1) @Max(200) long size) {
        return R.ok(invoiceService.page(chargeNo, page, size));
    }

    @Operation(summary = "登记人工开具的票号", description = "财政电子票据平台未接入时：在票据平台网页或原票据系统开具后登记")
    @PostMapping("/api/finance/invoices/{id}/manual-issue")
    @PreAuthorize("hasAuthority('finance:invoice')")
    public R<InvoiceVO> registerManual(@PathVariable Long id, @Valid @RequestBody ManualInvoiceDTO dto) {
        return R.ok(invoiceService.registerManual(id, dto.invoiceNo(), SecurityUtils.requireUser().getUserId()));
    }

    @Operation(summary = "电子票据平台是否已接入")
    @GetMapping("/api/finance/invoices/connected")
    @PreAuthorize("hasAuthority('finance:invoice')")
    public R<Boolean> connected() {
        return R.ok(invoiceService.connected());
    }
}
