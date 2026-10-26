package io.github.ryancamus.clinic.pharmacy.domain.vo;

import io.github.ryancamus.clinic.common.money.Money;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "药品目录")
public record DrugDetailVO(
        Long id,
        String drugCode,
        String insuranceCode,
        String name,
        String spec,
        String unit,
        Integer goodsType,
        Money retailPrice,
        Integer insuranceClass,
        Integer traceRequired,
        String manufacturer,
        String approvalNo,
        Integer safetyStock,
        Integer status) {
}
