package io.github.ryancamus.clinic.charge.domain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import io.github.ryancamus.clinic.common.money.Money;
import io.github.ryancamus.clinic.framework.mybatis.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 一笔退费在某一种原收款方式上要退回的金额。组合付款（如 校园卡 + 现金）的收费单退费时，
 * 按收款的逆序原路退回，每种方式各一行、各自执行。
 */
@Getter
@Setter
@TableName("refund_tender")
public class RefundTender extends BaseEntity {

    public static final int PENDING = 0;
    public static final int SUCCESS = 1;
    public static final int FAILED = 2;
    /** 现金、银行卡：系统不能自行退回，等收费员当面退还（银行卡在 POS 上退货）后确认 */
    public static final int AWAITING_CONFIRM = 3;

    private Long refundOrderId;
    private Long payOrderId;
    private Integer channel;
    private Money amount;
    private Integer status;
    private String channelRefundNo;
    /** 确认已退还的收费员、时间、所在班次（现金从该班次钱箱付出）与说明 */
    private Long confirmBy;
    private LocalDateTime confirmTime;
    private Long confirmShiftId;
    private String confirmNote;
}
