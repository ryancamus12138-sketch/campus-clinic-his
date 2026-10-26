package io.github.ryancamus.clinic.system.controller;

import io.github.ryancamus.clinic.common.result.R;
import io.github.ryancamus.clinic.system.domain.dto.CampusDTO;
import io.github.ryancamus.clinic.system.domain.dto.ChargePointDTO;
import io.github.ryancamus.clinic.system.domain.vo.CampusVO;
import io.github.ryancamus.clinic.system.domain.vo.ChargePointVO;
import io.github.ryancamus.clinic.system.domain.vo.OptionVO;
import io.github.ryancamus.clinic.system.service.ChargePointService;
import io.github.ryancamus.clinic.system.service.OrgService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 校区、收费点、科室、医生。查询登录即可访问，修改收费点需要管理员权限。
 */
@Tag(name = "系统-组织")
@RestController
@RequestMapping("/api/system")
@RequiredArgsConstructor
public class OrgController {

    private final OrgService orgService;
    private final ChargePointService chargePointService;

    @Operation(summary = "校区", description = "校区名称、地址在数据库中维护")
    @GetMapping("/campuses")
    public R<List<CampusVO>> campuses() {
        return R.ok(chargePointService.campuses());
    }

    @Operation(summary = "修改校区", description = "名称、地址按本校实际维护")
    @PutMapping("/campuses/{id}")
    @PreAuthorize("hasAuthority('system:charge-point')")
    public R<CampusVO> updateCampus(@PathVariable Long id, @Valid @RequestBody CampusDTO dto) {
        return R.ok(chargePointService.updateCampus(id, dto));
    }

    @Operation(summary = "收费点", description = "各校区的收费窗口及开放的窗口收款方式")
    @GetMapping("/charge-points")
    public R<List<ChargePointVO>> chargePoints() {
        return R.ok(chargePointService.list());
    }

    @Operation(summary = "修改收费点", description = "名称、开放的收款方式、启用状态")
    @PutMapping("/charge-points/{id}")
    @PreAuthorize("hasAuthority('system:charge-point')")
    public R<ChargePointVO> updateChargePoint(@PathVariable Long id, @Valid @RequestBody ChargePointDTO dto) {
        return R.ok(chargePointService.update(id, dto));
    }

    @Operation(summary = "科室选项")
    @GetMapping("/depts")
    public R<List<OptionVO>> depts(@RequestParam(required = false) Long campusId) {
        return R.ok(orgService.depts(campusId));
    }

    @Operation(summary = "医生选项")
    @GetMapping("/doctors")
    public R<List<OptionVO>> doctors(@RequestParam(required = false) Long deptId) {
        return R.ok(orgService.doctors(deptId));
    }
}
