package io.github.ryancamus.clinic.charge.domain.vo;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "收费相关外部平台的接入状态；未接入的在界面上显示“未接入”，或改为人工登记")
public record PlatformStatusVO(
        @Schema(description = "医保结算平台：未接入时收费员在医保结算终端办理后登记结算单号、统筹金额") boolean insurance,
        @Schema(description = "学校一卡通：未接入时窗口不能刷校园卡") boolean campusCard,
        @Schema(description = "统一支付平台（微信、支付宝付款码）：未接入时窗口不能扫付款码") boolean authCode) {
}
