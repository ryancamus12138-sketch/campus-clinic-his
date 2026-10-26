package io.github.ryancamus.clinic.pharmacy.domain.vo;

import io.github.ryancamus.clinic.common.money.Money;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDate;
import java.util.List;

@Schema(description = "药品库存")
public record StockVO(
        Long drugId,
        String drugCode,
        String name,
        String spec,
        String unit,
        Money retailPrice,
        int onHand,
        @Schema(description = "已开单待发药") int locked,
        int available,
        int safetyStock,
        @Schema(description = "可用量低于安全库存") boolean belowSafety,
        List<BatchVO> batches) {

    public record BatchVO(Long id, String batchNo, LocalDate expireDate, int onHand, int locked,
                          @Schema(description = "30 天内到期，不再用于发药") boolean nearExpiry) {
    }
}
