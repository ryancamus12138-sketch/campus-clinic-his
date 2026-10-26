package io.github.ryancamus.clinic.pharmacy.controller;

import io.github.ryancamus.clinic.common.result.R;
import io.github.ryancamus.clinic.framework.security.SecurityUtils;
import io.github.ryancamus.clinic.pharmacy.domain.dto.InboundDTO;
import io.github.ryancamus.clinic.pharmacy.domain.vo.DrugOptionVO;
import io.github.ryancamus.clinic.pharmacy.domain.vo.StockVO;
import io.github.ryancamus.clinic.pharmacy.service.DrugService;
import io.github.ryancamus.clinic.pharmacy.service.StockService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "药房-药品与库存")
@RestController
@RequestMapping("/api/pharmacy")
@RequiredArgsConstructor
public class DrugController {

    private final DrugService drugService;
    private final StockService stockService;

    @Operation(summary = "药品检索（开方用）", description = "不传院区时取当前登录人所在院区")
    @GetMapping("/drugs")
    @PreAuthorize("hasAnyAuthority('clinic:prescribe', 'pharmacy:stock:list')")
    public R<List<DrugOptionVO>> drugs(@RequestParam(required = false) Long campusId,
                                       @RequestParam(required = false) String keyword) {
        return R.ok(drugService.options(campusOrCurrent(campusId), keyword));
    }

    @Operation(summary = "批次库存")
    @GetMapping("/stock")
    @PreAuthorize("hasAuthority('pharmacy:stock:list')")
    public R<List<StockVO>> stock(@RequestParam(required = false) Long campusId,
                                  @RequestParam(required = false) String keyword) {
        return R.ok(drugService.stock(campusOrCurrent(campusId), keyword));
    }

    @Operation(summary = "采购入库", description = "同院区、同药品、同批号累加")
    @PostMapping("/inbound")
    @PreAuthorize("hasAuthority('pharmacy:stock:manage')")
    public R<Long> inbound(@Valid @RequestBody InboundDTO dto) {
        return R.ok(stockService.inbound(dto.campusId(), dto.drugId(), dto.batchNo(), dto.expireDate(),
                dto.purchasePrice(), dto.qty()));
    }

    private static Long campusOrCurrent(Long campusId) {
        if (campusId != null) {
            return campusId;
        }
        Long current = SecurityUtils.requireUser().getCampusId();
        return current == null ? 1L : current;
    }
}
