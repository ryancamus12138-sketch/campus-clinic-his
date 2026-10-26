package io.github.ryancamus.clinic.charge.domain.vo;

import io.github.ryancamus.clinic.common.money.Money;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

@Schema(description = "支付记录")
public record PayOrderVO(
        String payNo,
        @Schema(description = "1 现金 2 校园卡 3 支付宝 4 微信支付 6 银行卡") Integer channel,
        Money amount,
        @Schema(description = "1 成功（支付单在结算成功时写入）") Integer status,
        String channelTradeNo,
        LocalDateTime successTime,
        LocalDateTime createTime) {
}
