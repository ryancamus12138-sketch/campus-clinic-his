package io.github.ryancamus.clinic.patient.controller;

import io.github.ryancamus.clinic.common.result.R;
import io.github.ryancamus.clinic.patient.domain.dto.PatientCreateDTO;
import io.github.ryancamus.clinic.patient.domain.vo.PatientBriefVO;
import io.github.ryancamus.clinic.patient.domain.vo.PatientDetailVO;
import io.github.ryancamus.clinic.patient.domain.vo.PatientSensitiveVO;
import io.github.ryancamus.clinic.patient.service.PatientService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "患者档案")
@Validated
@RestController
@RequestMapping("/api/patients")
@RequiredArgsConstructor
public class PatientController {

    private final PatientService patientService;

    @Operation(summary = "检索患者", description = "按就诊卡号、学号/工号、证件号或手机号精确匹配")
    @GetMapping
    @PreAuthorize("hasAnyAuthority('charge:settle', 'charge:order:list', 'patient:create')")
    public R<List<PatientBriefVO>> search(@RequestParam @Size(max = 64) String keyword) {
        return R.ok(patientService.search(keyword));
    }

    @Operation(summary = "患者建档", description = "证件号、手机号加密存储；居民身份证会校验校验位并与出生日期、性别核对")
    @PostMapping
    @PreAuthorize("hasAuthority('patient:create')")
    public R<PatientDetailVO> create(@Valid @RequestBody PatientCreateDTO dto) {
        return R.ok(patientService.create(dto));
    }

    @Operation(summary = "患者档案详情", description = "证件号、手机号为脱敏值")
    @GetMapping("/{id}")
    @PreAuthorize("hasAnyAuthority('charge:settle', 'patient:create')")
    public R<PatientDetailVO> detail(@PathVariable Long id) {
        return R.ok(patientService.detail(id));
    }

    @Operation(summary = "查看完整证件号与手机号", description = "需要单独授权并填写原因，每次查看都记入审计日志")
    @GetMapping("/{id}/sensitive")
    @PreAuthorize("hasAuthority('patient:sensitive:view')")
    public R<PatientSensitiveVO> reveal(@PathVariable Long id, @RequestParam @Size(max = 100) String reason) {
        return R.ok(patientService.reveal(id, reason));
    }
}
