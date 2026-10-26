package io.github.ryancamus.clinic.finance.domain.vo;

import io.github.ryancamus.clinic.common.money.Money;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "收费员工作量：按办理班次汇总的收费、退费笔数与金额")
public record CashierWorkloadVO(
        Long cashierId,
        String cashierName,
        int chargeCount,
        Money chargeAmount,
        int refundCount,
        Money refundAmount) {
}
