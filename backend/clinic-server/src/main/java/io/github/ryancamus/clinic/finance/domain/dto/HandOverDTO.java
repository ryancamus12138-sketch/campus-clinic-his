package io.github.ryancamus.clinic.finance.domain.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

@Schema(description = "交班")
public record HandOverDTO(
        @NotNull @DecimalMin("0.00") @Schema(description = "实点现金") BigDecimal countedCash,
        @DecimalMin("-99999999.99") @Schema(description = "POS 机结算单金额（银行卡收款 − 退货）；本班有银行卡业务时必填")
        BigDecimal posCounted,
        @Size(max = 200) @Schema(description = "差额说明：实点现金或 POS 结算单与系统不一致时必填") String diffNote) {

    public HandOverDTO(BigDecimal countedCash) {
        this(countedCash, null, null);
    }
}
