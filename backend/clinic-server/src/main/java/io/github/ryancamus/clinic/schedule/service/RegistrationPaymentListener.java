package io.github.ryancamus.clinic.schedule.service;

import io.github.ryancamus.clinic.charge.api.ChargeApi;
import io.github.ryancamus.clinic.charge.api.ChargePaidEvent;
import io.github.ryancamus.clinic.charge.api.ChargeRefundedEvent;
import io.github.ryancamus.clinic.schedule.enums.RegistrationStatus;
import io.github.ryancamus.clinic.schedule.mapper.RegistrationMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 预约的收费单付清后，预约从“待支付”变为“已预约”；挂号费全额退回后取消预约（退号）。
 * <p>
 * 同步监听，在确认付款的同一事务内执行。不会与超时释放冲突：释放前必须先关闭收费单，
 * 收费单的“付清”与“关闭”只有一个能成功。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RegistrationPaymentListener {

    private final RegistrationMapper registrationMapper;
    private final BookingService bookingService;

    @EventListener
    public void onChargePaid(ChargePaidEvent event) {
        // 处方收费单也带挂号 ID，但它的付款与预约状态无关
        if (event.registrationId() == null || event.sourceType() == ChargeApi.SOURCE_PRESCRIPTION) {
            return;
        }
        if (registrationMapper.casStatus(event.registrationId(), RegistrationStatus.PENDING_PAYMENT,
                RegistrationStatus.BOOKED) == 0) {
            log.warn("预约已付款但状态不是待支付 registrationId={} chargeOrderId={}", event.registrationId(),
                    event.chargeOrderId());
        }
    }

    /** 挂号费全额退回：取消预约、归还号源（退号） */
    @EventListener
    public void onChargeRefunded(ChargeRefundedEvent event) {
        if (event.registrationId() != null && event.fullyRefunded() && event.sourceType() != ChargeApi.SOURCE_PRESCRIPTION) {
            bookingService.cancelAfterRefund(event.registrationId());
        }
    }
}
