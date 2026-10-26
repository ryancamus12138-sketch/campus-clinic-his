package io.github.ryancamus.clinic.charge.domain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import io.github.ryancamus.clinic.common.money.Money;
import io.github.ryancamus.clinic.framework.mybatis.BaseEntity;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("refund_item")
public class RefundItem extends BaseEntity {

    private Long refundOrderId;
    private Long chargeItemId;
    private Integer qty;
    private Money amount;
    private Money insuranceAmount;
    private Money selfAmount;
}
