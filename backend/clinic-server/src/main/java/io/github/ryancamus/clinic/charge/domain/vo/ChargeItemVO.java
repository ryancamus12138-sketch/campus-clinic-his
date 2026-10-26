package io.github.ryancamus.clinic.charge.domain.vo;

import io.github.ryancamus.clinic.common.money.Money;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;

@Schema(description = "收费明细")
public record ChargeItemVO(
        @Schema(description = "收费明细 ID（试算时为空）") Long id,
        String itemCode,
        String itemName,
        Integer itemType,
        Money unitPrice,
        Integer qty,
        Money amount,
        @Schema(description = "统筹支付比例，如 0.8000") BigDecimal insuranceRatio,
        @Schema(description = "医保统筹记账") Money insuranceAmount,
        @Schema(description = "个人自付") Money selfAmount,
        @Schema(description = "已退费（含处理中）") Integer refundedQty,
        @Schema(description = "已发药（药品、卫生材料）") Integer dispensedQty) {
}
