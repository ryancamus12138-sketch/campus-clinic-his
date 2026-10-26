package io.github.ryancamus.clinic.charge.controller;

import io.github.ryancamus.clinic.charge.domain.vo.PriceItemVO;
import io.github.ryancamus.clinic.charge.service.PriceItemService;
import io.github.ryancamus.clinic.common.result.R;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "收费-价目")
@RestController
@RequestMapping("/api/charge/price-items")
@RequiredArgsConstructor
public class PriceItemController {

    private final PriceItemService priceItemService;

    @Operation(summary = "当前生效价目")
    @GetMapping
    @PreAuthorize("hasAnyAuthority('schedule:slot:manage', 'charge:settle', 'charge:order:list', 'clinic:prescribe')")
    public R<List<PriceItemVO>> listCurrent(@RequestParam(required = false) Integer itemType) {
        return R.ok(priceItemService.listCurrent(itemType));
    }
}
