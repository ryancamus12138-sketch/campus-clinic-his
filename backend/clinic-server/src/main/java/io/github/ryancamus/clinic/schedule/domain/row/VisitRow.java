package io.github.ryancamus.clinic.schedule.domain.row;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;
import java.time.LocalTime;

/** 预约 + 号源的联表查询结果 */
@Getter
@Setter
public class VisitRow {

    private Long id;
    private String regNo;
    private Long patientId;
    private Integer status;
    private Long campusId;
    private Long deptId;
    private LocalDate slotDate;
    private LocalTime startTime;
}
