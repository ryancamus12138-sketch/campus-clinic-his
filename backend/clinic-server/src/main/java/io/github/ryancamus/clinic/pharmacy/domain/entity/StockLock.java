package io.github.ryancamus.clinic.pharmacy.domain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import io.github.ryancamus.clinic.framework.mybatis.BaseEntity;
import lombok.Getter;
import lombok.Setter;

/**
 * 收费明细在某批次上的锁定，按数量记录去向（见 V4 迁移脚本说明）。
 */
@Getter
@Setter
@TableName("stock_lock")
public class StockLock extends BaseEntity {

    public static final int LOCKED = 1;

    private Long chargeItemId;
    private Long batchId;
    private Integer qty;
    private Integer dispensedQty;
    private Integer releasedQty;
    private Integer returnedQty;
    private Integer status;

    /** 仍在锁定中、可发药的数量 */
    public int lockedRemaining() {
        return qty - dispensedQty - releasedQty;
    }

    /** 已发出、可退药的数量 */
    public int outstanding() {
        return dispensedQty - returnedQty;
    }
}
