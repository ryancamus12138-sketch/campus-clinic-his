package io.github.ryancamus.clinic.patient.domain.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import io.github.ryancamus.clinic.framework.mybatis.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;

/**
 * 患者档案。
 * <p>
 * 证件号、手机号只以密文（SM4-GCM）和盲索引（HMAC-SM3）落库。密文列标记为默认不查询，
 * 列表、详情等日常查询不会把密文读进内存；需要完整值时走单独的授权接口并记审计日志。
 */
@Getter
@Setter
@TableName("patient")
public class Patient extends BaseEntity {

    private Long campusId;
    private String patientNo;
    private String name;
    private Integer gender;
    private LocalDate birthDate;
    /** 1 学生 2 教职工 3 其他 */
    private Integer patientType;
    private String schoolNo;
    /** 0 自费 1 大学生医保 2 职工医保 3 公费医疗 */
    private Integer insuranceType;
    /** 证件类型，见 {@link io.github.ryancamus.clinic.patient.domain.IdDocuments} */
    private Integer idType;
    @TableField(value = "id_card_enc", select = false)
    private byte[] idNoEnc;
    @TableField(value = "id_card_hash", select = false)
    private String idNoHash;
    @TableField(select = false)
    private byte[] phoneEnc;
    @TableField(select = false)
    private String phoneHash;
}
