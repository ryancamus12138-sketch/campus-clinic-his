package io.github.ryancamus.clinic.finance.controller;

import io.github.ryancamus.clinic.common.result.R;
import io.github.ryancamus.clinic.finance.domain.vo.CashierWorkloadVO;
import io.github.ryancamus.clinic.finance.domain.vo.DeptRevenueVO;
import io.github.ryancamus.clinic.finance.domain.vo.ReportVO;
import io.github.ryancamus.clinic.finance.service.ReportService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

@Tag(name = "财务-统计报表")
@RestController
@RequestMapping("/api/finance/reports")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('finance:report')")
public class ReportController {

    private final ReportService reportService;

    @Operation(summary = "收费统计", description = "按日、项目类型、渠道、患者类型汇总；source=ledger 时直接扫流水（核对用）")
    @GetMapping("/summary")
    public R<ReportVO> summary(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                               @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                               @RequestParam(required = false) Long campusId,
                               @RequestParam(defaultValue = "agg") String source) {
        return R.ok(reportService.summary(from, to, campusId, "ledger".equals(source)));
    }

    @Operation(summary = "科室核算", description = "按开单科室汇总收入；收费记收费日、退费记退费日")
    @GetMapping("/departments")
    public R<List<DeptRevenueVO>> departments(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) Long campusId) {
        return R.ok(reportService.byDept(from, to, campusId));
    }

    @Operation(summary = "收费员工作量", description = "窗口办理的收费、退费笔数与金额")
    @GetMapping("/cashiers")
    public R<List<CashierWorkloadVO>> cashiers(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) Long campusId) {
        return R.ok(reportService.byCashier(from, to, campusId));
    }

    @Operation(summary = "重算某天的预聚合（校准）", description = "幂等，可随时重跑")
    @PostMapping("/rebuild")
    public R<Integer> rebuild(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return R.ok(reportService.rebuildDay(date));
    }
}
