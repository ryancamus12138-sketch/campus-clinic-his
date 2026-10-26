package io.github.ryancamus.clinic.charge.domain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import io.github.ryancamus.clinic.framework.mybatis.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 一次窗口结算尝试。进行中（0）与待人工处理（3）的记录会阻止这张收费单再收费、作废，直到结束或财务处理。
 */
@Getter
@Setter
@TableName("counter_checkout")
public class CounterCheckout extends BaseEntity {

    public static final int IN_PROGRESS = 0;
    public static final int COMPLETED = 1;
    public static final int REVERTED = 2;
    /** 撤回失败或中途中断，已收款项可能没有退回，等待财务核对 */
    public static final int NEEDS_ATTENTION = 3;
    public static final int RESOLVED = 4;

    private Long chargeOrderId;
    private Long cashierId;
    private String idempotencyKey;
    private Integer status;
    private String failReason;
    private String resolveNote;
    private Long resolverId;
    private LocalDateTime resolveTime;
}
