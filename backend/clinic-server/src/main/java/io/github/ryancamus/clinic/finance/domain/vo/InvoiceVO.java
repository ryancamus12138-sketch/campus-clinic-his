package io.github.ryancamus.clinic.finance.domain.vo;

import io.github.ryancamus.clinic.common.money.Money;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

@Schema(description = "医疗收费电子票据")
public record InvoiceVO(
        Long id,
        String invoiceNo,
        String chargeNo,
        @Schema(description = "1 正票 2 红票") Integer invoiceType,
        @Schema(description = "票面金额（原价合计）") Money amount,
        Money selfAmount,
        Money insuranceAmount,
        @Schema(description = "0 待开具（平台未接入时为待人工开具） 1 已开 2 失败") Integer status,
        String pdfUrl,
        LocalDateTime issueTime,
        @Schema(description = "1 平台开具 2 人工开具后登记") Integer issueMode,
        @Schema(description = "人工登记人") Long issuedBy) {
}
