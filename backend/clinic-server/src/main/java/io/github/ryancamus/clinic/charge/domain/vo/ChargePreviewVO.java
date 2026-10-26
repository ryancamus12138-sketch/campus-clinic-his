package io.github.ryancamus.clinic.charge.domain.vo;

import io.github.ryancamus.clinic.common.money.Money;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(description = "划价试算")
public record ChargePreviewVO(
        String feePolicyCode,
        String feePolicyName,
        List<ChargeItemVO> items,
        Money totalAmount,
        Money insuranceAmount,
        Money payableAmount) {
}
