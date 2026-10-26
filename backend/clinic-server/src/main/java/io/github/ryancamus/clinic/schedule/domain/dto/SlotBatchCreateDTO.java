package io.github.ryancamus.clinic.schedule.domain.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

@Schema(description = "批量排班放号：日期 × 时段生成号源，已存在的时段自动跳过")
public record SlotBatchCreateDTO(
        @NotNull Long campusId,
        @NotNull Long deptId,
        @Schema(description = "医生用户 ID；体检、疫苗不填") Long doctorId,
        @Schema(description = "1 门诊 2 新生体检 3 疫苗") @NotNull Integer slotType,
        @NotEmpty @Size(max = 31) List<@NotNull LocalDate> dates,
        @NotEmpty @Size(max = 24) List<@Valid Period> periods,
        @NotBlank @Size(max = 32) String priceItemCode,
        @Schema(description = "放号时间；不填表示立即开放") LocalDateTime openTime) {

    public record Period(
            @NotNull LocalTime startTime,
            @NotNull LocalTime endTime,
            @Schema(description = "该时段号源数") @NotNull @Min(1) @Max(2000) Integer total) {
    }
}
