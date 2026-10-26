package io.github.ryancamus.clinic.schedule.domain.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.github.ryancamus.clinic.common.money.Money;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

@Schema(description = "预约记录")
public record BookingVO(
        String regNo,
        @Schema(description = "0 待支付 1 已预约 2 已就诊 3 已取消 4 已过期") Integer status,
        Integer slotType,
        Long campusId,
        String deptName,
        String doctorName,
        LocalDate slotDate,
        @JsonFormat(pattern = "HH:mm") LocalTime startTime,
        @JsonFormat(pattern = "HH:mm") LocalTime endTime,
        @Schema(description = "原价；个人应付在缴费时按医保政策计算") Money price,
        @Schema(description = "待支付状态下的付款截止时间") LocalDateTime expireTime,
        LocalDateTime createTime) {
}
