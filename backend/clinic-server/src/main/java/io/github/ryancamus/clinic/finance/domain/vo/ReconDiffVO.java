package io.github.ryancamus.clinic.finance.domain.vo;

import io.github.ryancamus.clinic.common.money.Money;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Schema(description = "资金差异")
public record ReconDiffVO(
        Long id,
        LocalDate reconDate,
        Integer channel,
        @Schema(description = "1 长款 2 短款 3 金额不符") Integer diffType,
        String payNo,
        String channelTradeNo,
        Money ourAmount,
        Money channelAmount,
        @Schema(description = "0 待处理 1 已处理") Integer status,
        String remark,
        LocalDateTime createTime) {
}
