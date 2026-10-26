package io.github.ryancamus.clinic.charge.domain.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;

@Schema(description = "收费员确认现金已当面退还，或银行卡已在 POS 机上退货")
public record RefundConfirmDTO(
        @Size(max = 32) @Schema(description = "POS 退货小票上的参考号，银行卡必填") String reference,
        @Size(max = 200) @Schema(description = "备注，如：患者本人签收") String note) {
}
