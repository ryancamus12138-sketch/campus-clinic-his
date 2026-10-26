package io.github.ryancamus.clinic.finance.domain.vo;

import io.github.ryancamus.clinic.common.money.Money;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "科室核算：按开单科室汇总收入（收费记收费日，退费记退费日）")
public record DeptRevenueVO(
        @Schema(description = "为空表示收费处直接划价") Long deptId,
        String deptName,
        int orderCount,
        Money totalAmount,
        Money insuranceAmount,
        Money selfAmount,
        Money refundAmount,
        @Schema(description = "净收入 = 原价合计 − 退费（含统筹冲销）") Money netAmount) {
}
