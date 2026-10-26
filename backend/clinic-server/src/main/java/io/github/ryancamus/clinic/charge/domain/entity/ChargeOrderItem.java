package io.github.ryancamus.clinic.charge.domain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import io.github.ryancamus.clinic.common.money.Money;
import io.github.ryancamus.clinic.framework.mybatis.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * 收费明细。名称、单价、报销比例都是下单时的快照，之后改价或调整政策不影响已开单据。
 * (ref_type, ref_id) 在有效明细中唯一：同一挂号、同一处方明细不会被收两次费。
 */
@Getter
@Setter
@TableName("charge_order_item")
public class ChargeOrderItem extends BaseEntity {

    private Long chargeOrderId;
    private Integer itemType;
    private Integer refType;
    private Long refId;
    private String itemCode;
    private String itemName;
    private Money unitPrice;
    private Integer qty;
    private Money amount;
    private BigDecimal insuranceRatio;
    private Money insuranceAmount;
    private Money selfAmount;
    private Integer refundedQty;
    private Integer dispensedQty;
    private Integer valid;
}
