package io.github.ryancamus.clinic.charge.domain.vo;

import io.github.ryancamus.clinic.common.money.Money;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(description = "收费窗口结算结果")
public record CheckoutResultVO(
        String chargeNo,
        @Schema(description = "医疗费总额") Money totalAmount,
        @Schema(description = "医保统筹支付") Money fundPay,
        @Schema(description = "个人自付（本次收取）") Money selfAmount,
        @Schema(description = "医保结算流水号，无医保报销时为空") String setlId,
        @Schema(description = "现金找零") Money change,
        List<TenderVO> tenders) {

    public record TenderVO(String payNo, Integer channel, Money amount, String channelTradeNo, String tenderRef) {
    }
}
