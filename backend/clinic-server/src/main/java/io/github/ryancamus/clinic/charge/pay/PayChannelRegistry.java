package io.github.ryancamus.clinic.charge.pay;

import io.github.ryancamus.clinic.charge.enums.ChargeErrorCode;
import io.github.ryancamus.clinic.common.exception.BizException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

@Component
public class PayChannelRegistry {

    private final List<PayChannelClient> clients;

    public PayChannelRegistry(ObjectProvider<PayChannelClient> clients) {
        // 允许一个渠道都没有（尚未接入统一支付平台），此时窗口只收现金和银行卡
        this.clients = clients.orderedStream().toList();
    }

    public Optional<PayChannelClient> find(int channel) {
        return clients.stream().filter(c -> c.supports(channel)).findFirst();
    }

    public PayChannelClient require(int channel) {
        return find(channel).orElseThrow(() -> new BizException(ChargeErrorCode.CHANNEL_NOT_SUPPORTED));
    }
}
