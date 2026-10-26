package io.github.ryancamus.clinic.patient.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import io.github.ryancamus.clinic.audit.api.AuditApi;
import io.github.ryancamus.clinic.common.exception.BizException;
import io.github.ryancamus.clinic.common.util.Masks;
import io.github.ryancamus.clinic.framework.crypto.FieldCipher;
import io.github.ryancamus.clinic.framework.util.BizNoGenerator;
import io.github.ryancamus.clinic.patient.api.PatientQueryApi;
import io.github.ryancamus.clinic.patient.domain.IdDocuments;
import io.github.ryancamus.clinic.patient.domain.dto.PatientCreateDTO;
import io.github.ryancamus.clinic.patient.domain.entity.Patient;
import io.github.ryancamus.clinic.patient.domain.vo.PatientBriefVO;
import io.github.ryancamus.clinic.patient.domain.vo.PatientDetailVO;
import io.github.ryancamus.clinic.patient.domain.vo.PatientSensitiveVO;
import io.github.ryancamus.clinic.patient.enums.PatientErrorCode;
import io.github.ryancamus.clinic.patient.mapper.PatientMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 患者档案：建档、检索、查看。
 * <p>
 * 证件号、手机号的处理原则：
 * <ul>
 *     <li>落库只有密文和盲索引，数据库被拖走也拿不到明文；</li>
 *     <li>检索走盲索引精确匹配，不需要解密，也不支持模糊查询；</li>
 *     <li>接口默认返回脱敏值；完整值需要单独权限，并且必须填写原因，每次查看都记审计日志。</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class PatientService implements PatientQueryApi {

    static final String PURPOSE_ID_NO = "patient.id_no";
    static final String PURPOSE_PHONE = "patient.phone";
    private static final int SEARCH_LIMIT = 20;

    private final PatientMapper patientMapper;
    private final FieldCipher cipher;
    private final AuditApi auditApi;
    private final Clock clock;

    @Override
    public Optional<PatientSnapshot> findById(Long patientId) {
        return Optional.ofNullable(patientMapper.selectById(patientId))
                .map(p -> new PatientSnapshot(p.getId(), p.getCampusId(), p.getPatientNo(), p.getName(),
                        p.getPatientType(), p.getInsuranceType()));
    }

    @Override
    public Map<Long, String> names(Collection<Long> patientIds) {
        if (patientIds.isEmpty()) {
            return Map.of();
        }
        return patientMapper.selectList(Wrappers.<Patient>lambdaQuery()
                        .select(Patient::getId, Patient::getName)
                        .in(Patient::getId, patientIds))
                .stream().collect(Collectors.toMap(Patient::getId, Patient::getName));
    }

    /**
     * 收费处检索：就诊卡号、学号/工号、证件号、手机号，全部是精确匹配并且走唯一索引或盲索引。
     * 不支持按姓名模糊查询，既避免全表扫描，也避免收费员随意浏览患者名单。
     */
    public List<PatientBriefVO> search(String keyword) {
        String kw = keyword == null ? "" : keyword.trim();
        if (kw.isEmpty()) {
            return List.of();
        }
        String normalized = IdDocuments.normalize(kw);
        String idHash = cipher.blindIndex(PURPOSE_ID_NO, normalized);
        String phoneHash = IdDocuments.isMobile(normalized) ? cipher.blindIndex(PURPOSE_PHONE, normalized) : null;
        return patientMapper.selectList(Wrappers.<Patient>lambdaQuery()
                        .eq(Patient::getPatientNo, kw).or().eq(Patient::getSchoolNo, kw)
                        .or().eq(Patient::getIdNoHash, idHash)
                        .or(phoneHash != null, w -> w.eq(Patient::getPhoneHash, phoneHash))
                        .last("LIMIT " + SEARCH_LIMIT))
                .stream()
                .map(p -> new PatientBriefVO(p.getId(), p.getPatientNo(), p.getName(), p.getSchoolNo(),
                        p.getPatientType(), p.getInsuranceType(), p.getCampusId()))
                .toList();
    }

    public PatientDetailVO create(PatientCreateDTO dto) {
        String idNo = IdDocuments.normalize(dto.idNo());
        String phone = IdDocuments.normalize(dto.phone());
        Optional<IdDocuments.Embedded> embedded;
        try {
            embedded = IdDocuments.validate(dto.idType(), idNo);
        } catch (IdDocuments.InvalidIdException e) {
            throw new BizException(PatientErrorCode.INVALID_ID_NO);
        }
        embedded.ifPresent(e -> {
            if (!e.birthDate().equals(dto.birthDate()) || e.gender() != dto.gender()) {
                throw new BizException(PatientErrorCode.ID_NO_MISMATCH);
            }
        });
        if (!IdDocuments.isMobile(phone)) {
            throw new BizException(PatientErrorCode.INVALID_PHONE);
        }
        String schoolNo = dto.schoolNo() == null || dto.schoolNo().isBlank() ? null : dto.schoolNo().trim();
        if (dto.patientType() != 3 && schoolNo == null) {
            throw new BizException(PatientErrorCode.SCHOOL_NO_REQUIRED);
        }

        Patient p = new Patient();
        p.setCampusId(dto.campusId());
        p.setPatientNo(BizNoGenerator.next("P", clock));
        p.setName(dto.name().trim());
        p.setGender(dto.gender());
        p.setBirthDate(dto.birthDate());
        p.setPatientType(dto.patientType());
        p.setSchoolNo(schoolNo);
        p.setInsuranceType(dto.insuranceType());
        p.setIdType(dto.idType());
        p.setIdNoEnc(cipher.encrypt(PURPOSE_ID_NO, idNo));
        p.setIdNoHash(cipher.blindIndex(PURPOSE_ID_NO, idNo));
        p.setPhoneEnc(cipher.encrypt(PURPOSE_PHONE, phone));
        p.setPhoneHash(cipher.blindIndex(PURPOSE_PHONE, phone));
        try {
            patientMapper.insert(p);
        } catch (DuplicateKeyException e) {
            String message = e.getMostSpecificCause().getMessage();
            throw new BizException(message != null && message.contains("uk_school_no")
                    ? PatientErrorCode.DUPLICATE_SCHOOL_NO : PatientErrorCode.DUPLICATE_ID_NO);
        }
        return toDetail(p, idNo, phone);
    }

    /** 档案详情：证件号、手机号为脱敏值（解密后在内存中脱敏，不落库、不返回明文） */
    public PatientDetailVO detail(Long id) {
        Patient p = require(id);
        Patient secrets = patientMapper.selectSecrets(id);
        return toDetail(p, cipher.decrypt(PURPOSE_ID_NO, secrets.getIdNoEnc()),
                cipher.decrypt(PURPOSE_PHONE, secrets.getPhoneEnc()));
    }

    /** 查看完整证件号与手机号：调用方已校验权限，这里强制填写原因并记审计日志 */
    public PatientSensitiveVO reveal(Long id, String reason) {
        if (reason == null || reason.isBlank()) {
            throw new BizException(PatientErrorCode.REASON_REQUIRED);
        }
        require(id);
        Patient secrets = patientMapper.selectSecrets(id);
        PatientSensitiveVO vo = new PatientSensitiveVO(id, secrets.getIdType(),
                cipher.decrypt(PURPOSE_ID_NO, secrets.getIdNoEnc()),
                cipher.decrypt(PURPOSE_PHONE, secrets.getPhoneEnc()));
        auditApi.record("PATIENT_SENSITIVE_VIEW", "查看患者完整证件号与手机号", "patientId=" + id, true,
                "reason=" + reason.trim());
        return vo;
    }

    private Patient require(Long id) {
        Patient p = patientMapper.selectById(id);
        if (p == null) {
            throw new BizException(PatientErrorCode.PATIENT_NOT_FOUND);
        }
        return p;
    }

    private static PatientDetailVO toDetail(Patient p, String idNo, String phone) {
        return new PatientDetailVO(p.getId(), p.getPatientNo(), p.getName(), p.getGender(), p.getBirthDate(),
                p.getPatientType(), p.getSchoolNo(), p.getInsuranceType(), p.getIdType(), Masks.idNo(idNo),
                Masks.phone(phone), p.getCampusId());
    }
}
