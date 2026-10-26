package io.github.ryancamus.clinic.clinic.service;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import io.github.ryancamus.clinic.charge.api.ChargeApi.ChargeOrderView;
import io.github.ryancamus.clinic.charge.api.ChargeApi;
import io.github.ryancamus.clinic.clinic.domain.dto.PrescriptionCreateDTO;
import io.github.ryancamus.clinic.clinic.domain.entity.Prescription;
import io.github.ryancamus.clinic.clinic.domain.entity.PrescriptionItem;
import io.github.ryancamus.clinic.clinic.domain.vo.PrescriptionVO;
import io.github.ryancamus.clinic.clinic.domain.vo.VisitVO;
import io.github.ryancamus.clinic.clinic.enums.ClinicErrorCode;
import io.github.ryancamus.clinic.clinic.mapper.PrescriptionItemMapper;
import io.github.ryancamus.clinic.clinic.mapper.PrescriptionMapper;
import io.github.ryancamus.clinic.common.exception.BizException;
import io.github.ryancamus.clinic.framework.util.BizNoGenerator;
import io.github.ryancamus.clinic.patient.api.PatientQueryApi;
import io.github.ryancamus.clinic.schedule.api.RegistrationApi.Visit;
import io.github.ryancamus.clinic.schedule.api.RegistrationApi;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 门诊开方。医生提交处方即生成收费单并锁定药品库存（开单即划价），
 * 患者到收费窗口缴费后，凭收费单号到药房取药。
 */
@Service
@RequiredArgsConstructor
public class PrescriptionService {

    private static final String RX_NO_PREFIX = "RX";

    private final PrescriptionMapper prescriptionMapper;
    private final PrescriptionItemMapper itemMapper;
    private final RegistrationApi registrationApi;
    private final ChargeApi chargeApi;
    private final PatientQueryApi patientQueryApi;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;

    public List<VisitVO> visits(Long campusId, Long deptId, LocalDate date) {
        List<Visit> visits = registrationApi.listVisits(campusId, deptId, date);
        Map<Long, String> names = patientQueryApi.names(visits.stream().map(Visit::patientId).distinct().toList());
        return visits.stream().map(v -> new VisitVO(v.registrationId(), v.regNo(), v.patientId(),
                names.get(v.patientId()), v.status(), v.slotDate(), v.startTime())).toList();
    }

    public PrescriptionVO create(PrescriptionCreateDTO dto, Long doctorId) {
        Visit visit = registrationApi.findVisit(dto.registrationId())
                .orElseThrow(() -> new BizException(ClinicErrorCode.VISIT_NOT_FOUND));
        BizException.throwIf(visit.status() != 1 && visit.status() != 2, ClinicErrorCode.VISIT_NOT_ACTIVE);
        for (PrescriptionCreateDTO.Line line : dto.items()) {
            boolean goods = line.kind() == PrescriptionItem.KIND_GOODS && line.drugId() != null;
            boolean treatment = line.kind() == PrescriptionItem.KIND_TREATMENT && line.itemCode() != null
                    && !line.itemCode().isBlank();
            BizException.throwIf(!goods && !treatment, ClinicErrorCode.INVALID_RX_ITEM);
        }

        return transactionTemplate.execute(tx -> {
            Prescription rx = new Prescription();
            rx.setId(IdWorker.getId());
            rx.setRxNo(BizNoGenerator.next(RX_NO_PREFIX, clock));
            rx.setRegistrationId(visit.registrationId());
            rx.setPatientId(visit.patientId());
            rx.setDoctorId(doctorId);
            rx.setStatus(Prescription.ISSUED);
            prescriptionMapper.insert(rx);

            List<ChargeApi.PrescriptionLine> lines = new ArrayList<>();
            for (PrescriptionCreateDTO.Line line : dto.items()) {
                PrescriptionItem item = new PrescriptionItem();
                item.setId(IdWorker.getId());
                item.setRxId(rx.getId());
                item.setItemKind(line.kind());
                item.setGoodsId(line.kind() == PrescriptionItem.KIND_GOODS ? line.drugId() : null);
                item.setItemCode(line.kind() == PrescriptionItem.KIND_TREATMENT ? line.itemCode().trim() : null);
                item.setQty(line.qty());
                item.setUsageNote(line.usageNote());
                itemMapper.insert(item);
                lines.add(new ChargeApi.PrescriptionLine(item.getId(), item.getGoodsId(), item.getItemCode(),
                        item.getQty()));
            }
            ChargeOrderView charge = chargeApi.createForPrescription(new ChargeApi.PrescriptionCharge(rx.getId(),
                    visit.registrationId(), visit.patientId(), visit.campusId(), visit.deptId(), lines));
            rx.setChargeOrderId(charge.id());
            rx.setStatus(Prescription.PRICED);
            prescriptionMapper.updateById(rx);
            registrationApi.markVisited(visit.registrationId());
            chargeApi.markRegistrationServed(visit.registrationId());
            return toVO(rx, charge);
        });
    }

    public List<PrescriptionVO> listByRegistration(Long registrationId) {
        return prescriptionMapper.selectList(Wrappers.<Prescription>lambdaQuery()
                        .eq(Prescription::getRegistrationId, registrationId)
                        .orderByDesc(Prescription::getId))
                .stream()
                .map(rx -> toVO(rx, rx.getChargeOrderId() == null ? null
                        : chargeApi.findView(rx.getChargeOrderId()).orElse(null)))
                .toList();
    }

    private static PrescriptionVO toVO(Prescription rx, ChargeOrderView charge) {
        return new PrescriptionVO(rx.getId(), rx.getRxNo(), rx.getStatus(),
                charge == null ? null : charge.chargeNo(), charge == null ? null : charge.status(),
                charge == null ? null : charge.totalAmount(), charge == null ? null : charge.insuranceAmount(),
                charge == null ? null : charge.payableAmount(), rx.getCreateTime());
    }
}
