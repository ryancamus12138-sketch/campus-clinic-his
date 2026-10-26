package io.github.ryancamus.clinic.schedule.controller;

import io.github.ryancamus.clinic.charge.api.ChargeApi;
import io.github.ryancamus.clinic.common.result.R;
import io.github.ryancamus.clinic.schedule.domain.dto.RegisterDTO;
import io.github.ryancamus.clinic.schedule.domain.query.AvailableSlotQuery;
import io.github.ryancamus.clinic.schedule.domain.vo.BookingVO;
import io.github.ryancamus.clinic.schedule.domain.vo.RegistrationResultVO;
import io.github.ryancamus.clinic.schedule.domain.vo.SlotVO;
import io.github.ryancamus.clinic.schedule.service.BookingService;
import io.github.ryancamus.clinic.schedule.service.SlotService;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 窗口挂号：学生、教职工到挂号收费窗口，收费员选号源、生成挂号费收费单，随后在窗口收费。
 */
@Tag(name = "挂号（收费窗口）")
@RestController
@RequestMapping("/api/schedule/registrations")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('charge:settle')")
public class RegistrationController {

    private final BookingService bookingService;
    private final SlotService slotService;

    @Operation(summary = "可挂号的号源", description = "slotType：1 门诊 2 新生体检 3 疫苗")
    @GetMapping("/slots")
    public R<List<SlotVO>> availableSlots(@Valid AvailableSlotQuery query) {
        return R.ok(slotService.listAvailable(query));
    }

    @Operation(summary = "挂号", description = "需缴费的号源同时生成挂号费收费单，未缴费的超过期限自动退号")
    @PostMapping
    public R<RegistrationResultVO> register(@Valid @RequestBody RegisterDTO dto) {
        return R.ok(bookingService.register(dto.patientId(), dto.slotId()));
    }

    @Operation(summary = "患者最近的挂号记录")
    @GetMapping
    public R<List<BookingVO>> list(@RequestParam Long patientId) {
        return R.ok(bookingService.listByPatient(patientId));
    }

    @Operation(summary = "取得挂号费收费单", description = "重复调用返回同一张收费单")
    @PostMapping("/{regNo}/charge")
    public R<ChargeApi.ChargeOrderView> charge(@PathVariable String regNo) {
        return R.ok(bookingService.prepareCharge(regNo));
    }

    @Operation(summary = "退号（未缴费）", description = "已缴费的走退费，退费完成后自动退号")
    @PostMapping("/{regNo}/cancel")
    public R<Void> cancel(@PathVariable String regNo) {
        bookingService.cancel(regNo);
        return R.ok();
    }
}
