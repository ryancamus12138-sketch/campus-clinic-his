package io.github.ryancamus.clinic.clinic.domain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import io.github.ryancamus.clinic.framework.mybatis.BaseEntity;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("prescription_item")
public class PrescriptionItem extends BaseEntity {

    public static final int KIND_GOODS = 1;
    public static final int KIND_TREATMENT = 2;

    private Long rxId;
    /** 1 药品/卫生材料 2 诊疗项目 */
    private Integer itemKind;
    private Long goodsId;
    private String itemCode;
    private Integer qty;
    private String usageNote;
}
