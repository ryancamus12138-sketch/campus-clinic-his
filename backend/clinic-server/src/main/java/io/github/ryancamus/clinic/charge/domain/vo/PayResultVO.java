package io.github.ryancamus.clinic.charge.domain.vo;

import io.github.ryancamus.clinic.common.money.Money;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "现金收费的结果")
public record PayResultVO(
        String payNo,
        String chargeNo,
        Integer channel,
        Money amount,
        @Schema(description = "1 成功") Integer status,
        @Schema(description = "现金收费的找零") Money change) {
}
