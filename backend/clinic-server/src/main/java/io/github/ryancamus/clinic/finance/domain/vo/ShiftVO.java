package io.github.ryancamus.clinic.finance.domain.vo;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Schema(description = "收费员班次")
public record ShiftVO(
        Long id,
        String shiftNo,
        Long cashierId,
        String cashierName,
        @Schema(description = "收费点") Long chargePointId,
        String chargePointName,
        @Schema(description = "0 当班 1 已交班 2 已复核") Integer status,
        LocalDateTime startTime,
        LocalDateTime endTime,
        BigDecimal cashAmount,
        BigDecimal cardAmount,
        BigDecimal onlineAmount,
        @Schema(description = "本班办理的退款合计") BigDecimal refundAmount,
        @Schema(description = "钱箱应有现金 = 现金收款 − 现金退款") BigDecimal expectedCash,
        @Schema(description = "收费员实点现金") BigDecimal countedCash,
        @Schema(description = "实点 − 应有；非 0 需说明") BigDecimal cashDiff,
        Integer orderCount,
        @Schema(description = "POS 应有：银行卡收款 − 银行卡退款") BigDecimal posExpected,
        @Schema(description = "收费员录入的 POS 机结算单金额") BigDecimal posCounted,
        @Schema(description = "结算单 − 应有；非 0 需说明") BigDecimal posDiff,
        @Schema(description = "当班的银行卡收款、退款笔数（只在当班时返回）；大于 0 时交班须录入 POS 结算单") Integer posCount,
        @Schema(description = "长短款说明") String diffNote,
        Long reviewerId,
        LocalDateTime reviewTime,
        String reviewNote) {
}
