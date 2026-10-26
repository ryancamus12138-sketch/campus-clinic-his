package io.github.ryancamus.clinic.pharmacy.domain.vo;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDate;
import java.util.List;

@Schema(description = "待发药收费单")
public record DispenseOrderVO(
        String chargeNo,
        String patientName,
        List<Item> items) {

    public record Item(
            Long chargeItemId,
            String itemCode,
            String itemName,
            int qty,
            int dispensedQty,
            @Schema(description = "已退费或退费处理中") int refundedQty,
            @Schema(description = "还可发") int dispensable,
            boolean traceRequired,
            List<Lock> locks) {
    }

    public record Lock(String batchNo, LocalDate expireDate, int locked, int dispensed, int returned) {
    }
}
