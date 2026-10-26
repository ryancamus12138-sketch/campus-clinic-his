package io.github.ryancamus.clinic.finance.domain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import io.github.ryancamus.clinic.common.money.Money;
import io.github.ryancamus.clinic.framework.mybatis.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 收费员交班单。交班时把本班流水汇总写入，之后本班不会再有新流水（新的收费自动进入下一班）。
 */
@Getter
@Setter
@TableName("cashier_shift")
public class CashierShift extends BaseEntity {

    public static final int OPEN = 0;
    public static final int HANDED_OVER = 1;
    public static final int REVIEWED = 2;

    private String shiftNo;
    private Long campusId;
    /** 收费点（收费窗口） */
    private Long chargePointId;
    private Long cashierId;
    private Integer status;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private Money cashAmount;
    private Money cardAmount;
    private Money onlineAmount;
    private Money refundAmount;
    private Money countedCash;
    /** 可能为负（本班现金退款多于收款），因此不用 Money */
    private BigDecimal expectedCash;
    private Integer orderCount;
    private BigDecimal posExpected;
    private BigDecimal posCounted;
    private Long reviewerId;
    /** 长短款说明（交班人填写）、复核时间与复核意见 */
    private String diffNote;
    private LocalDateTime reviewTime;
    private String reviewNote;
}
