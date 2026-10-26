package io.github.ryancamus.clinic.system.domain.vo;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(description = "收费点")
public record ChargePointVO(
        Long id,
        Long campusId,
        String code,
        String name,
        @Schema(description = "开放的窗口收款方式：CASH 现金、BANK_CARD 银行卡、CAMPUS_CARD 校园卡、AUTH_CODE 付款码")
        List<String> counterMethods,
        @Schema(description = "1 启用 0 停用") Integer status) {
}
