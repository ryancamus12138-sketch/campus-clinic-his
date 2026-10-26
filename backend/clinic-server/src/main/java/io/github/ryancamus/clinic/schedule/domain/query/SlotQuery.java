package io.github.ryancamus.clinic.schedule.domain.query;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import java.time.LocalDate;

@Schema(description = "号源分页查询（员工端）")
public record SlotQuery(
        @Min(1) Integer page,
        @Min(1) @Max(200) Integer size,
        Long campusId,
        Long deptId,
        Integer slotType,
        LocalDate dateFrom,
        LocalDate dateTo) {

    public long pageOrDefault() {
        return page == null ? 1 : page;
    }

    public long sizeOrDefault() {
        return size == null ? 50 : size;
    }
}
