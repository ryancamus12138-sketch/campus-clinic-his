package io.github.ryancamus.clinic.charge.controller;

import io.github.ryancamus.clinic.charge.domain.dto.CashPayDTO;
import io.github.ryancamus.clinic.charge.domain.dto.ChargeCreateDTO;
import io.github.ryancamus.clinic.charge.domain.dto.CheckoutDTO;
import io.github.ryancamus.clinic.charge.domain.query.ChargeOrderQuery;
import io.github.ryancamus.clinic.charge.domain.vo.ChargeOrderVO;
import io.github.ryancamus.clinic.charge.domain.vo.ChargePreviewVO;
import io.github.ryancamus.clinic.charge.domain.vo.CheckoutResultVO;
import io.github.ryancamus.clinic.charge.domain.vo.PayResultVO;
import io.github.ryancamus.clinic.charge.domain.vo.PlatformStatusVO;
import io.github.ryancamus.clinic.charge.service.ChargeOrderService;
import io.github.ryancamus.clinic.charge.service.CounterCheckoutService;
import io.github.ryancamus.clinic.common.result.PageResult;
import io.github.ryancamus.clinic.common.result.R;
import io.github.ryancamus.clinic.framework.security.SecurityUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "收费-收费单（收费处）")
@RestController
@RequestMapping("/api/charge/orders")
@RequiredArgsConstructor
public class ChargeOrderController {

    private final ChargeOrderService chargeOrderService;
    private final CounterCheckoutService counterCheckoutService;

    @Operation(summary = "收费单分页查询")
    @GetMapping
    @PreAuthorize("hasAuthority('charge:order:list')")
    public R<PageResult<ChargeOrderVO>> page(@Valid ChargeOrderQuery query) {
        return R.ok(chargeOrderService.page(query));
    }

    @Operation(summary = "收费单详情（含明细与支付记录）")
    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('charge:order:list')")
    public R<ChargeOrderVO> detail(@PathVariable Long id) {
        return R.ok(chargeOrderService.detail(id));
    }

    @Operation(summary = "划价试算", description = "按患者参保类型计算统筹记账与个人自付，不生成单据")
    @PostMapping("/preview")
    @PreAuthorize("hasAuthority('charge:settle')")
    public R<ChargePreviewVO> preview(@Valid @RequestBody ChargeCreateDTO dto) {
        return R.ok(chargeOrderService.preview(dto));
    }

    @Operation(summary = "划价生成收费单")
    @PostMapping
    @PreAuthorize("hasAuthority('charge:settle')")
    public R<ChargeOrderVO> create(@Valid @RequestBody ChargeCreateDTO dto) {
        return R.ok(chargeOrderService.createCounterOrder(dto));
    }

    @Operation(summary = "现金收费", description = "请求头 Idempotency-Key 由前端每次结算生成一次，网络重试时保持不变")
    @PostMapping("/{id}/cash")
    @PreAuthorize("hasAuthority('charge:settle')")
    public R<PayResultVO> cash(@PathVariable Long id, @Valid @RequestBody CashPayDTO dto,
                               @RequestHeader("Idempotency-Key") String idempotencyKey) {
        return R.ok(counterCheckoutService.cashPay(id, dto.received(), idempotencyKey, SecurityUtils.requireUser().getUserId()));
    }

    @Operation(summary = "窗口结算", description = "有医保报销的先办理医保结算（医保平台未接入时须在 insurance 字段登记"
            + "医保结算终端的结算单号和统筹金额），再按本收费点开放的一种或多种方式收取自付。中途失败时撤销医保结算；"
            + "已收现金、POS 交易等不能确认已退回的款项转为收款异常。请求头 Idempotency-Key 同现金收费")
    @PostMapping("/{id}/checkout")
    @PreAuthorize("hasAuthority('charge:settle')")
    public R<CheckoutResultVO> checkout(@PathVariable Long id, @Valid @RequestBody CheckoutDTO dto,
                                        @RequestHeader("Idempotency-Key") String idempotencyKey) {
        return R.ok(counterCheckoutService.checkout(id, dto, idempotencyKey, SecurityUtils.requireUser().getUserId()));
    }

    @Operation(summary = "当前可用的窗口收款方式", description = "收费点开放、且对应平台已接入的方式；"
            + "校园卡、付款码在未接入一卡通、统一支付平台时不可用。campusId 为收费单所在校区")
    @GetMapping("/tender-methods")
    @PreAuthorize("hasAuthority('charge:settle')")
    public R<List<CheckoutDTO.Method>> tenderMethods(@RequestParam(required = false) Long campusId) {
        return R.ok(counterCheckoutService.availableMethods(campusId));
    }

    @Operation(summary = "外部平台接入状态", description = "医保结算、一卡通、统一支付平台（付款码）是否已接入")
    @GetMapping("/platforms")
    @PreAuthorize("hasAuthority('charge:settle')")
    public R<PlatformStatusVO> platforms() {
        return R.ok(counterCheckoutService.platformStatus());
    }

    @Operation(summary = "作废未缴费的收费单")
    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasAuthority('charge:settle')")
    public R<Void> cancel(@PathVariable Long id) {
        chargeOrderService.cancel(id);
        return R.ok();
    }
}
