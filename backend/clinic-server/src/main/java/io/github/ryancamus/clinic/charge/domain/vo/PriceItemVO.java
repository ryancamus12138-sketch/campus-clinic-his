package io.github.ryancamus.clinic.charge.domain.vo;

import io.github.ryancamus.clinic.common.money.Money;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "当前生效的价目")
public record PriceItemVO(
        String itemCode,
        String itemName,
        @Schema(description = "1 挂号 3 诊疗 4 检查 6 体检 7 疫苗服务") Integer itemType,
        String unit,
        Money unitPrice,
        @Schema(description = "是否医保可报销") boolean insurable) {
}
