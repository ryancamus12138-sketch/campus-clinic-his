package io.github.ryancamus.clinic.clinic.domain.vo;

import io.github.ryancamus.clinic.common.money.Money;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

@Schema(description = "处方")
public record PrescriptionVO(
        Long id,
        String rxNo,
        Integer status,
        String chargeNo,
        @Schema(description = "收费单状态：0 待支付 2 已支付 ...") Integer chargeStatus,
        Money totalAmount,
        Money insuranceAmount,
        Money payableAmount,
        LocalDateTime createTime) {
}
