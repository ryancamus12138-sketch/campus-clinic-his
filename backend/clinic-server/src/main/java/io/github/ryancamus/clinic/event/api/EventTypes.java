package io.github.ryancamus.clinic.event.api;

/**
 * 发件箱事件类型。
 */
public final class EventTypes {

    /** 收费单付清。内容：chargeOrderId, chargeNo, patientId, totalAmount, insuranceAmount, selfAmount */
    public static final String CHARGE_PAID = "CHARGE_PAID";

    /** 一笔退费成功。内容：chargeOrderId, chargeNo, patientId, refundNo, 以及退费后剩余的 totalAmount, insuranceAmount, selfAmount */
    public static final String CHARGE_REFUNDED = "CHARGE_REFUNDED";

    private EventTypes() {
    }
}
