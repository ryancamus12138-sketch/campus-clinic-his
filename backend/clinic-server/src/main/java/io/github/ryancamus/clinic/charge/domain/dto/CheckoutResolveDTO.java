package io.github.ryancamus.clinic.charge.domain.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Schema(description = "财务核对收款异常后的处理说明")
public record CheckoutResolveDTO(
        @NotBlank @Size(max = 200) @Schema(description = "如：已在 POS 机撤销；已在一卡通后台冲正；核对未扣款") String note,
        @Schema(description = "医保结算撤销一直失败时：已在医保端手工撤销") boolean insuranceCancelledManually) {

    public CheckoutResolveDTO(String note) {
        this(note, false);
    }
}
