package io.github.ryancamus.clinic.schedule.api;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

/**
 * 预约模块对门诊模块开放的就诊信息：医生的候诊列表、开方时确认挂号有效、接诊后标记已就诊。
 */
public interface RegistrationApi {

    Optional<Visit> findVisit(Long registrationId);

    /** 某院区某天的门诊候诊与已诊列表（已预约、已就诊） */
    List<Visit> listVisits(Long campusId, Long deptId, LocalDate date);

    /** 已预约 → 已就诊；已经是已就诊视为成功 */
    boolean markVisited(Long registrationId);

    /**
     * @param status 1 已预约 2 已就诊
     */
    record Visit(Long registrationId, String regNo, Long patientId, int status, Long campusId, Long deptId,
                 LocalDate slotDate, LocalTime startTime) {
    }
}
