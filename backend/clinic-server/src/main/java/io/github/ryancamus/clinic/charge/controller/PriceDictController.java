package io.github.ryancamus.clinic.charge.controller;

import io.github.ryancamus.clinic.charge.domain.dto.PriceItemCreateDTO;
import io.github.ryancamus.clinic.charge.domain.dto.PriceVersionDTO;
import io.github.ryancamus.clinic.charge.domain.vo.PriceItemAdminVO;
import io.github.ryancamus.clinic.charge.service.PriceItemAdminService;
import io.github.ryancamus.clinic.common.result.R;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "字典-收费项目")
@RestController
@RequestMapping("/api/dict/price-items")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('dict:price:manage')")
public class PriceDictController {

    private final PriceItemAdminService service;

    @Operation(summary = "收费项目", description = "当前价格与待生效的调价")
    @GetMapping
    public R<List<PriceItemAdminVO>> list(@RequestParam(required = false) Integer itemType) {
        return R.ok(service.list(itemType));
    }

    @Operation(summary = "新增收费项目", description = "立即生效")
    @PostMapping
    public R<PriceItemAdminVO> create(@Valid @RequestBody PriceItemCreateDTO dto) {
        return R.ok(service.create(dto));
    }

    @Operation(summary = "调价", description = "录入新价格和生效时间，到点自动切换；已划价的收费单仍按原价")
    @PostMapping("/{itemCode}/versions")
    public R<PriceItemAdminVO> schedule(@PathVariable String itemCode, @Valid @RequestBody PriceVersionDTO dto) {
        return R.ok(service.schedule(itemCode, dto));
    }

    @Operation(summary = "撤销待生效的调价")
    @DeleteMapping("/{itemCode}/versions/pending")
    public R<PriceItemAdminVO> cancelPending(@PathVariable String itemCode) {
        return R.ok(service.cancelPending(itemCode));
    }
}
