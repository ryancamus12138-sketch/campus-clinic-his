package io.github.ryancamus.clinic.charge.pay.mock;

import io.github.ryancamus.clinic.charge.enums.PayChannel;
import io.github.ryancamus.clinic.charge.pay.PayChannelClient;
import io.github.ryancamus.clinic.common.money.Money;

import java.time.LocalDate;
import java.util.List;

/**
 * 付款码渠道接口 {@link io.github.ryancamus.clinic.charge.pay.PayChannelClient} 的测试替身（Test Double）。
 * 只存在于 src/test，仅用于自动化测试；不是生产环境的外部平台适配器，不会进入生产构建。
 * <p>
 * 对接内存中的 {@link MockPayGateway}，同时承担支付宝、微信两个渠道编码。
 */
public class MockPayChannelClient implements PayChannelClient {

    private final MockPayGateway gateway;

    public MockPayChannelClient(MockPayGateway gateway) {
        this.gateway = gateway;
    }

    @Override
    public boolean supports(int channel) {
        return channel == PayChannel.ALIPAY.code() || channel == PayChannel.WECHAT.code();
    }

    @Override
    public ChannelTrade micropay(int channel, String payNo, String authCode, Money amount, String subject) {
        return gateway.micropay(channel, payNo, authCode, amount, subject);
    }

    @Override
    public ChannelTrade query(String payNo) {
        return gateway.query(payNo);
    }

    @Override
    public boolean close(String payNo) {
        return gateway.close(payNo);
    }

    @Override
    public RefundResult refund(String payNo, String refundNo, Money refundAmount, Money totalAmount) {
        return gateway.refund(payNo, refundNo, refundAmount);
    }

    @Override
    public List<ChannelTrade> downloadBill(int channel, LocalDate date) {
        return gateway.bill(channel, date);
    }
}
