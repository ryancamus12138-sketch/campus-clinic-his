package io.github.ryancamus.clinic.charge.domain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import io.github.ryancamus.clinic.common.money.Money;
import io.github.ryancamus.clinic.framework.mybatis.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 价目表。价格分版本：改价时插入新版本并结束旧版本的生效区间，历史单据仍能追溯当时的价格。
 */
@Getter
@Setter
@TableName("price_item")
public class PriceItem extends BaseEntity {

    private String itemCode;
    private String itemName;
    /** 医疗服务项目编码（医保目录对照） */
    private String insuranceCode;
    private Integer itemType;
    private String unit;
    private Money unitPrice;
    /** 0 不可报销 1 可报销 */
    private Integer insuranceClass;
    private LocalDateTime effectiveFrom;
    /** 为空表示当前生效 */
    private LocalDateTime effectiveTo;
}
