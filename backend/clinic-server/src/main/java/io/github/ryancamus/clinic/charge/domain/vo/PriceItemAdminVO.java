package io.github.ryancamus.clinic.charge.domain.vo;

import io.github.ryancamus.clinic.common.money.Money;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

@Schema(description = "收费项目（含当前价格与待生效的调价）")
public record PriceItemAdminVO(
        String itemCode,
        String itemName,
        String insuranceCode,
        Integer itemType,
        String unit,
        Version current,
        @Schema(description = "待生效的调价，没有则为空") Version pending) {

    public record Version(Money unitPrice, Integer insuranceClass, LocalDateTime effectiveFrom,
                          LocalDateTime effectiveTo) {
    }
}
