package io.github.ryancamus.clinic.finance.api;

import io.github.ryancamus.clinic.common.money.Money;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 对账需要的两份数据：本系统某天某渠道的成功收款，以及渠道当天的账单。
 * 接口由财务模块定义、收费模块实现，财务不直接依赖收费（依赖倒置）。
 */
public interface ChannelBillPort {

    /** 医保基金：与医保中心对明细账时使用的渠道编号 */
    int INSURANCE = 9;

    /** 需要对账的线上渠道 */
    List<Integer> reconcilableChannels();

    List<Entry> ourPayments(int channel, LocalDate date);

    List<Entry> channelBill(int channel, LocalDate date);

    /**
     * @param payNo   商户订单号（本系统支付单号）
     * @param tradeNo 渠道交易号
     */
    record Entry(String payNo, String tradeNo, Money amount, LocalDateTime time) {
    }
}
