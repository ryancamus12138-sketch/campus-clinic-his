package io.github.ryancamus.clinic.system.convert;

import io.github.ryancamus.clinic.system.domain.entity.SysUser;
import io.github.ryancamus.clinic.system.domain.vo.UserVO;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

/**
 * 实体与 VO 的转换，编译期生成代码，没有反射开销。
 */
@Mapper(componentModel = "spring")
public interface UserConvert {

    @Mapping(target = "roleIds", ignore = true)
    UserVO toVO(SysUser user);
}
