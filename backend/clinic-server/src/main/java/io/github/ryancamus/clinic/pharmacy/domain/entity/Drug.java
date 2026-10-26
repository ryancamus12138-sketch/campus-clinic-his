package io.github.ryancamus.clinic.pharmacy.domain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import io.github.ryancamus.clinic.common.money.Money;
import io.github.ryancamus.clinic.framework.mybatis.BaseEntity;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("drug")
public class Drug extends BaseEntity {

    private String drugCode;
    /** 国家医保药品分类与代码 */
    private String insuranceCode;
    private String name;
    private String spec;
    private String unit;
    /** 1 药品 2 卫生材料 */
    private Integer goodsType;
    private Money retailPrice;
    private Integer insuranceClass;
    private Integer traceRequired;
    private String manufacturer;
    private String approvalNo;
    private Integer safetyStock;
    private Integer status;
}
