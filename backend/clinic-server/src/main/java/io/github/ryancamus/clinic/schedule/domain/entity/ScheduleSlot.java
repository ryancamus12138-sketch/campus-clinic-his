package io.github.ryancamus.clinic.schedule.domain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import io.github.ryancamus.clinic.framework.mybatis.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

@Getter
@Setter
@TableName("schedule_slot")
public class ScheduleSlot extends BaseEntity {

    public static final int STATUS_CLOSED = 0;
    public static final int STATUS_OPEN = 1;
    public static final int STATUS_STOPPED = 2;

    private Long campusId;
    private Long deptId;
    /** 体检、疫苗时段为 0 */
    private Long doctorId;
    private Integer slotType;
    private LocalDate slotDate;
    private LocalTime startTime;
    private LocalTime endTime;
    private Integer total;
    private Integer remaining;
    private String priceItemCode;
    private Integer status;
    private LocalDateTime openTime;

    public LocalDateTime startAt() {
        return LocalDateTime.of(slotDate, startTime);
    }

    public LocalDateTime endAt() {
        return LocalDateTime.of(slotDate, endTime);
    }
}
