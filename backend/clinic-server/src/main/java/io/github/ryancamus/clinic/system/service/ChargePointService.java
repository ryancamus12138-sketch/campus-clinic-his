package io.github.ryancamus.clinic.system.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import io.github.ryancamus.clinic.common.exception.BizException;
import io.github.ryancamus.clinic.system.domain.dto.CampusDTO;
import io.github.ryancamus.clinic.system.domain.dto.ChargePointDTO;
import io.github.ryancamus.clinic.system.domain.entity.ChargePoint;
import io.github.ryancamus.clinic.system.domain.entity.SysCampus;
import io.github.ryancamus.clinic.system.domain.vo.CampusVO;
import io.github.ryancamus.clinic.system.domain.vo.ChargePointVO;
import io.github.ryancamus.clinic.system.enums.SystemErrorCode;
import io.github.ryancamus.clinic.system.mapper.ChargePointMapper;
import io.github.ryancamus.clinic.system.mapper.SysCampusMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 校区与收费点。各校的校区、收费窗口和开放的收款方式不同，都在数据库中维护，不写死在代码或页面里。
 */
@Service
@RequiredArgsConstructor
public class ChargePointService {

    private final SysCampusMapper campusMapper;
    private final ChargePointMapper chargePointMapper;

    public List<CampusVO> campuses() {
        return campusMapper.selectList(Wrappers.<SysCampus>lambdaQuery().orderByAsc(SysCampus::getId)).stream()
                .map(c -> new CampusVO(c.getId(), c.getName(), c.getAddress())).toList();
    }

    public CampusVO updateCampus(Long id, CampusDTO dto) {
        SysCampus c = campusMapper.selectById(id);
        BizException.throwIf(c == null, SystemErrorCode.CAMPUS_NOT_FOUND);
        c.setName(dto.name().trim());
        c.setAddress(dto.address() == null || dto.address().isBlank() ? null : dto.address().trim());
        try {
            campusMapper.updateById(c);
        } catch (DuplicateKeyException e) {
            throw new BizException(SystemErrorCode.CAMPUS_NAME_EXISTS);
        }
        return new CampusVO(c.getId(), c.getName(), c.getAddress());
    }

    public List<ChargePointVO> list() {
        return chargePointMapper.selectList(Wrappers.<ChargePoint>lambdaQuery()
                        .orderByAsc(ChargePoint::getCampusId, ChargePoint::getSort, ChargePoint::getId))
                .stream().map(ChargePointService::toVO).toList();
    }

    public ChargePointVO update(Long id, ChargePointDTO dto) {
        ChargePoint c = chargePointMapper.selectById(id);
        BizException.throwIf(c == null, SystemErrorCode.CHARGE_POINT_NOT_FOUND);
        c.setName(dto.name().trim());
        c.setCounterMethods(String.join(",", new LinkedHashSet<>(dto.counterMethods())));
        c.setStatus(dto.status() == 1 ? 1 : 0);
        chargePointMapper.updateById(c);
        return toVO(chargePointMapper.selectById(id));
    }

    static Set<String> methods(ChargePoint c) {
        Set<String> set = new LinkedHashSet<>();
        Arrays.stream(c.getCounterMethods().split(",")).map(String::trim).filter(m -> !m.isEmpty()).forEach(set::add);
        return set;
    }

    private static ChargePointVO toVO(ChargePoint c) {
        return new ChargePointVO(c.getId(), c.getCampusId(), c.getCode(), c.getName(), List.copyOf(methods(c)),
                c.getStatus());
    }
}
