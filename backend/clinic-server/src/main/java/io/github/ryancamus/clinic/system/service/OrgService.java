package io.github.ryancamus.clinic.system.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import io.github.ryancamus.clinic.system.domain.entity.SysDept;
import io.github.ryancamus.clinic.system.domain.vo.OptionVO;
import io.github.ryancamus.clinic.system.mapper.SysDeptMapper;
import io.github.ryancamus.clinic.system.mapper.SysUserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 组织数据的下拉选项：院区下的科室、科室下的医生。
 */
@Service
@RequiredArgsConstructor
public class OrgService {

    private final SysDeptMapper deptMapper;
    private final SysUserMapper userMapper;

    public List<OptionVO> depts(Long campusId) {
        return deptMapper.selectList(Wrappers.<SysDept>lambdaQuery()
                        .eq(campusId != null, SysDept::getCampusId, campusId)
                        .eq(SysDept::getStatus, 1)
                        .orderByAsc(SysDept::getCampusId, SysDept::getSort))
                .stream()
                .map(d -> new OptionVO(d.getId(), d.getDeptName(), d.getCampusId()))
                .toList();
    }

    public List<OptionVO> doctors(Long deptId) {
        return userMapper.selectByRole("DOCTOR", deptId).stream()
                .map(u -> new OptionVO(u.getId(), u.getNickname(), u.getDeptId()))
                .toList();
    }
}
