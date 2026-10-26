package io.github.ryancamus.clinic.schedule.domain.vo;

import io.github.ryancamus.clinic.charge.api.ChargeApi.ChargeOrderView;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "窗口挂号结果")
public record RegistrationResultVO(
        BookingVO registration,
        @Schema(description = "挂号费收费单；免费号为空") ChargeOrderView chargeOrder) {
}
