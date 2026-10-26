package io.github.ryancamus.clinic.charge.service;

import io.github.ryancamus.clinic.charge.domain.entity.ChargeOrder;
import io.github.ryancamus.clinic.common.money.Money;
import io.github.ryancamus.clinic.event.api.EventTypes;
import io.github.ryancamus.clinic.event.api.OutboxPublisher;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 收费模块写入发件箱的事件。下游（电子票据等）需要的数据全部放在事件里，
 * 消费方不必回查收费模块，模块之间没有反向依赖。
 */
@Component
@RequiredArgsConstructor
public class ChargeOutbox {

    private final OutboxPublisher publisher;

    public void paid(ChargeOrder order) {
        Map<String, Object> payload = base(order);
        payload.put("totalAmount", order.getTotalAmount().toString());
        payload.put("insuranceAmount", order.getInsuranceAmount().toString());
        payload.put("selfAmount", order.getPayableAmount().toString());
        publisher.publish(EventTypes.CHARGE_PAID, order.getId(), payload);
    }

    /**
     * @param remainingTotal 退费后剩余的原价合计（用于重开票据）
     */
    public void refunded(ChargeOrder order, String refundNo, Money remainingTotal, Money remainingInsurance,
                         Money remainingSelf) {
        Map<String, Object> payload = base(order);
        payload.put("refundNo", refundNo);
        payload.put("totalAmount", remainingTotal.toString());
        payload.put("insuranceAmount", remainingInsurance.toString());
        payload.put("selfAmount", remainingSelf.toString());
        publisher.publish(EventTypes.CHARGE_REFUNDED, order.getId(), payload);
    }

    private static Map<String, Object> base(ChargeOrder order) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("chargeOrderId", order.getId().toString());
        payload.put("chargeNo", order.getChargeNo());
        payload.put("patientId", order.getPatientId().toString());
        return payload;
    }
}
