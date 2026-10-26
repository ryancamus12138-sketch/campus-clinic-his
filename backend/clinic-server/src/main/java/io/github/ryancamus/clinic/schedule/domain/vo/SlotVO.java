package io.github.ryancamus.clinic.schedule.domain.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.github.ryancamus.clinic.common.money.Money;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

@Schema(description = "号源")
public record SlotVO(
        Long id,
        Long campusId,
        Long deptId,
        String deptName,
        Long doctorId,
        String doctorName,
        Integer slotType,
        LocalDate slotDate,
        @JsonFormat(pattern = "HH:mm") LocalTime startTime,
        @JsonFormat(pattern = "HH:mm") LocalTime endTime,
        Integer total,
        @Schema(description = "剩余量；已开放抢约的号源取 Redis 实时值") Integer remaining,
        String priceItemCode,
        @Schema(description = "当前单价") Money price,
        @Schema(description = "0 未开放 1 开放 2 停诊") Integer status,
        LocalDateTime openTime) {
}
