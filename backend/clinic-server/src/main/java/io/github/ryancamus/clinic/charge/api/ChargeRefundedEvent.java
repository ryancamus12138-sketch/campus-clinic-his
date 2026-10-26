package io.github.ryancamus.clinic.charge.api;

/**
 * 退款成功。在退款确认的事务内同步发布。
 *
 * @param fullyRefunded 收费单已全部退完
 */
public record ChargeRefundedEvent(Long chargeOrderId, int sourceType, Long registrationId, Long patientId,
                                  boolean fullyRefunded) {
}
