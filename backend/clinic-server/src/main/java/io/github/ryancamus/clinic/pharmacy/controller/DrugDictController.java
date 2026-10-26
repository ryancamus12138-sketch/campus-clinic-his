package io.github.ryancamus.clinic.pharmacy.controller;

import io.github.ryancamus.clinic.common.result.PageResult;
import io.github.ryancamus.clinic.common.result.R;
import io.github.ryancamus.clinic.pharmacy.domain.dto.DrugSaveDTO;
import io.github.ryancamus.clinic.pharmacy.domain.vo.DrugDetailVO;
import io.github.ryancamus.clinic.pharmacy.service.DrugAdminService;
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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "字典-药品目录")
@Validated
@RestController
@RequestMapping("/api/dict/drugs")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('dict:drug:manage')")
public class DrugDictController {

    private final DrugAdminService drugAdminService;

    @Operation(summary = "药品目录", description = "按编码前缀、名称或医保编码检索")
    @GetMapping
    public R<PageResult<DrugDetailVO>> page(@RequestParam(required = false) String keyword,
                                           @RequestParam(defaultValue = "1") @Min(1) long page,
                                           @RequestParam(defaultValue = "20") @Min(1) @Max(200) long size) {
        return R.ok(drugAdminService.page(keyword, page, size));
    }

    @Operation(summary = "新增药品")
    @PostMapping
    public R<DrugDetailVO> create(@Valid @RequestBody DrugSaveDTO dto) {
        return R.ok(drugAdminService.create(dto));
    }

    @Operation(summary = "修改药品", description = "改价只影响之后的划价；停用后不能再开具")
    @PutMapping("/{id}")
    public R<DrugDetailVO> update(@PathVariable Long id, @Valid @RequestBody DrugSaveDTO dto) {
        return R.ok(drugAdminService.update(id, dto));
    }
}
