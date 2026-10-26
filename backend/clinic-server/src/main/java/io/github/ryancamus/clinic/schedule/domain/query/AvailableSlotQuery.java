package io.github.ryancamus.clinic.schedule.domain.query;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;

@Schema(description = "窗口挂号：可挂号的号源查询")
public record AvailableSlotQuery(
        @Schema(description = "1 门诊 2 新生体检 3 疫苗") @NotNull Integer slotType,
        @NotNull LocalDate date,
        Long campusId,
        Long deptId) {
}
