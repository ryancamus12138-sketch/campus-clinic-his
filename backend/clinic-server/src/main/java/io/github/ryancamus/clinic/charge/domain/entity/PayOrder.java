package io.github.ryancamus.clinic.charge.domain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import io.github.ryancamus.clinic.common.money.Money;
import io.github.ryancamus.clinic.framework.mybatis.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 支付单：一次窗口结算中一种收款方式的一笔收款，结算成功时写入。三个唯一键各防一类重复：
 * pay_no（本系统收款单号）、idempotency_key（客户端重复提交）、
 * (channel, channel_trade_no)（同一笔渠道交易不重复登记）。
 */
@Getter
@Setter
@TableName("pay_order")
public class PayOrder extends BaseEntity {

    private String payNo;
    private Long chargeOrderId;
    private Integer channel;
    private Money amount;
    private Integer status;
    private String idempotencyKey;
    private String channelTradeNo;
    /** 窗口收款凭据：校园卡号（脱敏）、POS 参考号 */
    private String tenderRef;
    private Money refundedAmount;
    private LocalDateTime expireTime;
    private LocalDateTime successTime;
}
