package io.github.ryancamus.clinic.patient.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.github.ryancamus.clinic.patient.domain.entity.Patient;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;


public interface PatientMapper extends BaseMapper<Patient> {

    /** 读取密文列（实体上默认不查询），仅供授权查看完整证件号、手机号时使用 */
    @Select("SELECT id, id_type, id_card_enc AS id_no_enc, phone_enc FROM patient WHERE id = #{id}")
    Patient selectSecrets(@Param("id") Long id);
}
