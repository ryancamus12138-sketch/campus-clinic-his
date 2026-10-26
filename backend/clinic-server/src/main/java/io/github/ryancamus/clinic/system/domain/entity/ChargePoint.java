package io.github.ryancamus.clinic.system.domain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import io.github.ryancamus.clinic.framework.mybatis.BaseEntity;
import lombok.Getter;
import lombok.Setter;

/**
 * 收费点：某校区的一个收费窗口，以及它开放的窗口收款方式。
 */
@Getter
@Setter
@TableName("charge_point")
public class ChargePoint extends BaseEntity {

    private Long campusId;
    private String code;
    private String name;
    /** 逗号分隔：CASH,BANK_CARD,CAMPUS_CARD,AUTH_CODE */
    private String counterMethods;
    /** 1 启用 0 停用 */
    private Integer status;
    private Integer sort;
}
