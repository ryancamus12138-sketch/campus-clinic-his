package io.github.ryancamus.clinic.pharmacy.controller;

import io.github.ryancamus.clinic.common.result.R;
import io.github.ryancamus.clinic.pharmacy.domain.dto.DispenseDTO;
import io.github.ryancamus.clinic.pharmacy.domain.dto.ReturnDTO;
import io.github.ryancamus.clinic.pharmacy.domain.vo.DispenseOrderVO;
import io.github.ryancamus.clinic.pharmacy.service.DispenseService;
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
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "药房-发药退药")
@RestController
@RequestMapping("/api/pharmacy/dispense")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('pharmacy:dispense')")
public class DispenseController {

    private final DispenseService dispenseService;

    @Operation(summary = "按收费单号调出待发药明细")
    @GetMapping("/{chargeNo}")
    public R<DispenseOrderVO> view(@PathVariable String chargeNo) {
        return R.ok(dispenseService.view(chargeNo));
    }

    @Operation(summary = "发药", description = "需要扫码的药品逐盒扫描追溯码")
    @PostMapping
    public R<Void> dispense(@Valid @RequestBody DispenseDTO dto) {
        dispenseService.dispense(dto);
        return R.ok();
    }

    @Operation(summary = "退药", description = "退回后该数量才能在收费处退费")
    @PostMapping("/return")
    public R<Void> returnDrug(@Valid @RequestBody ReturnDTO dto) {
        dispenseService.returnDrug(dto);
        return R.ok();
    }
}
