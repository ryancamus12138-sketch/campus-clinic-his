package io.github.ryancamus.clinic.clinic.controller;

import io.github.ryancamus.clinic.clinic.domain.dto.PrescriptionCreateDTO;
import io.github.ryancamus.clinic.clinic.domain.vo.PrescriptionVO;
import io.github.ryancamus.clinic.clinic.domain.vo.VisitVO;
import io.github.ryancamus.clinic.clinic.service.PrescriptionService;
import io.github.ryancamus.clinic.common.result.R;
import io.github.ryancamus.clinic.framework.security.LoginUser;
import io.github.ryancamus.clinic.framework.security.SecurityUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;

@Tag(name = "门诊-接诊开方")
@RestController
@RequestMapping("/api/clinic")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('clinic:prescribe')")
public class ClinicController {

    private final PrescriptionService prescriptionService;
    private final Clock clock;

    @Operation(summary = "候诊列表", description = "默认当天、本人所在院区；deptId 不传则看全院区门诊")
    @GetMapping("/visits")
    public R<List<VisitVO>> visits(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(required = false) Long deptId) {
        LoginUser user = SecurityUtils.requireUser();
        Long campusId = user.getCampusId() == null ? 1L : user.getCampusId();
        return R.ok(prescriptionService.visits(campusId, deptId, date == null ? LocalDate.now(clock) : date));
    }

    @Operation(summary = "开具处方", description = "同时生成收费单并锁定药品库存，库存不足时整张处方不保存")
    @PostMapping("/prescriptions")
    public R<PrescriptionVO> create(@Valid @RequestBody PrescriptionCreateDTO dto) {
        return R.ok(prescriptionService.create(dto, SecurityUtils.requireUser().getUserId()));
    }

    @Operation(summary = "某次挂号的处方")
    @GetMapping("/prescriptions")
    public R<List<PrescriptionVO>> list(@RequestParam Long registrationId) {
        return R.ok(prescriptionService.listByRegistration(registrationId));
    }
}
