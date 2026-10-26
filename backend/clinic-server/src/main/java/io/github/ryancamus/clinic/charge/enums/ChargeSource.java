package io.github.ryancamus.clinic.charge.enums;

/**
 * 收费单来源（charge_order.source_type）与明细来源（charge_order_item.ref_type）。
 */
public final class ChargeSource {

    public static final int REGISTRATION = 1;
    /** 门诊：医生开方生成的，以及收费处直接划价的（二者以明细来源 ref_type 区分） */
    public static final int PRESCRIPTION = 2;
    public static final int PHYSICAL_EXAM = 3;
    public static final int VACCINE = 4;

    /** 明细来源：收费处手工划价，ref_id 为明细自身 ID */
    public static final int REF_MANUAL = 5;

    private ChargeSource() {
    }
}
