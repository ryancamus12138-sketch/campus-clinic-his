package io.github.ryancamus.clinic.pharmacy.domain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import io.github.ryancamus.clinic.common.money.Money;
import io.github.ryancamus.clinic.framework.mybatis.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;

/**
 * 批次库存。CHECK 约束保证 在库 ≥ 0、锁定 ≥ 0、锁定 ≤ 在库，任何并发错误都会在数据库层失败而不是产生负库存。
 */
@Getter
@Setter
@TableName("drug_batch")
public class DrugBatch extends BaseEntity {

    private Long campusId;
    private Long drugId;
    private String batchNo;
    private LocalDate expireDate;
    private Money purchasePrice;
    private Integer qtyOnHand;
    private Integer qtyLocked;

    public int available() {
        return qtyOnHand - qtyLocked;
    }
}
