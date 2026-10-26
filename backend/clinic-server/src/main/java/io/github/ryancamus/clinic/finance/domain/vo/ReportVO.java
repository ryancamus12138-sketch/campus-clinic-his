package io.github.ryancamus.clinic.finance.domain.vo;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@Schema(description = "收费统计")
public record ReportVO(
        LocalDate from,
        LocalDate to,
        @Schema(description = "数据来源：agg 预聚合 + 当天实时；ledger 直接扫流水（核对用）") String source,
        Line total,
        List<DayLine> days,
        List<Line> byItemType,
        List<Line> byChannel,
        List<Line> byPatientType) {

    /**
     * @param key        维度取值（项目类型、渠道、患者类型的编码）
     * @param netAmount  实收净额 = 自付 − 退款
     */
    public record Line(Integer key, int orderCount, BigDecimal totalAmount, BigDecimal insuranceAmount,
                       BigDecimal selfAmount, BigDecimal refundAmount, BigDecimal netAmount) {
    }

    public record DayLine(LocalDate date, BigDecimal totalAmount, BigDecimal insuranceAmount, BigDecimal selfAmount,
                          BigDecimal refundAmount, BigDecimal netAmount) {
    }
}
