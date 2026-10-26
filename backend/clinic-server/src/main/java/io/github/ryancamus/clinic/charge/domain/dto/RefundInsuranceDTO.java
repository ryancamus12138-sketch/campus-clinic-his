package io.github.ryancamus.clinic.charge.domain.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;

@Schema(description = "医保平台未接入时，退费在医保结算终端办理的结果")
public record RefundInsuranceDTO(
        @Valid @Schema(description = "按剩余项目重新结算的结果；全部退完、没有剩余医保报销时不填")
        CheckoutDTO.InsuranceRegistration resettlement) {
}
