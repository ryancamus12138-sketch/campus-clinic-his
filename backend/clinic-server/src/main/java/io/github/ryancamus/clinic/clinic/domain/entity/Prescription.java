package io.github.ryancamus.clinic.clinic.domain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import io.github.ryancamus.clinic.framework.mybatis.BaseEntity;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("prescription")
public class Prescription extends BaseEntity {

    public static final int ISSUED = 0;
    public static final int PRICED = 1;

    private String rxNo;
    private Long registrationId;
    private Long patientId;
    private Long doctorId;
    private Long chargeOrderId;
    /** 0 已开立 1 已划价 2 已撤销 */
    private Integer status;
}
