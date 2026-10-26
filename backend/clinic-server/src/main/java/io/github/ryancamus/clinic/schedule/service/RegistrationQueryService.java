package io.github.ryancamus.clinic.schedule.service;

import io.github.ryancamus.clinic.schedule.api.RegistrationApi;
import io.github.ryancamus.clinic.schedule.domain.row.VisitRow;
import io.github.ryancamus.clinic.schedule.enums.RegistrationStatus;
import io.github.ryancamus.clinic.schedule.mapper.RegistrationMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class RegistrationQueryService implements RegistrationApi {

    private final RegistrationMapper registrationMapper;

    @Override
    public Optional<Visit> findVisit(Long registrationId) {
        return Optional.ofNullable(registrationMapper.selectVisit(registrationId)).map(RegistrationQueryService::toVisit);
    }

    @Override
    public List<Visit> listVisits(Long campusId, Long deptId, LocalDate date) {
        return registrationMapper.selectVisits(campusId, deptId, date).stream()
                .map(RegistrationQueryService::toVisit).toList();
    }

    @Override
    public boolean markVisited(Long registrationId) {
        if (registrationMapper.casStatus(registrationId, RegistrationStatus.BOOKED, RegistrationStatus.VISITED) == 1) {
            return true;
        }
        VisitRow row = registrationMapper.selectVisit(registrationId);
        return row != null && row.getStatus() == RegistrationStatus.VISITED;
    }

    private static Visit toVisit(VisitRow r) {
        return new Visit(r.getId(), r.getRegNo(), r.getPatientId(), r.getStatus(), r.getCampusId(), r.getDeptId(),
                r.getSlotDate(), r.getStartTime());
    }
}
