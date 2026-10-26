package io.github.ryancamus.clinic.charge.domain.query;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import java.time.LocalDate;

@Schema(description = "收费单分页查询")
public record ChargeOrderQuery(
        @Min(1) Integer page,
        @Min(1) @Max(200) Integer size,
        String chargeNo,
        Long patientId,
        Integer status,
        LocalDate dateFrom,
        LocalDate dateTo) {

    public long pageOrDefault() {
        return page == null ? 1 : page;
    }

    public long sizeOrDefault() {
        return size == null ? 20 : size;
    }
}
