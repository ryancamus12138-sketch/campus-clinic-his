package io.github.ryancamus.clinic.schedule.controller;

import io.github.ryancamus.clinic.common.result.PageResult;
import io.github.ryancamus.clinic.common.result.R;
import io.github.ryancamus.clinic.schedule.domain.dto.SlotBatchCreateDTO;
import io.github.ryancamus.clinic.schedule.domain.dto.SlotStatusDTO;
import io.github.ryancamus.clinic.schedule.domain.query.SlotQuery;
import io.github.ryancamus.clinic.schedule.domain.vo.SlotVO;
import io.github.ryancamus.clinic.schedule.service.SlotService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "号源管理（员工）")
@RestController
@RequestMapping("/api/schedule/slots")
@RequiredArgsConstructor
public class SlotController {

    private final SlotService slotService;

    @Operation(summary = "号源分页")
    @GetMapping
    @PreAuthorize("hasAuthority('schedule:slot:list')")
    public R<PageResult<SlotVO>> page(@Valid SlotQuery query) {
        return R.ok(slotService.page(query));
    }

    @Operation(summary = "批量排班放号", description = "日期 × 时段生成号源，已存在的时段跳过；返回实际新增数量")
    @PostMapping("/batch")
    @PreAuthorize("hasAuthority('schedule:slot:manage')")
    public R<Integer> batchCreate(@Valid @RequestBody SlotBatchCreateDTO dto) {
        return R.ok(slotService.batchCreate(dto));
    }

    @Operation(summary = "开放/关闭/停诊")
    @PutMapping("/{id}/status")
    @PreAuthorize("hasAuthority('schedule:slot:manage')")
    public R<Void> changeStatus(@PathVariable Long id, @Valid @RequestBody SlotStatusDTO dto) {
        slotService.changeStatus(id, dto.status());
        return R.ok();
    }
}
