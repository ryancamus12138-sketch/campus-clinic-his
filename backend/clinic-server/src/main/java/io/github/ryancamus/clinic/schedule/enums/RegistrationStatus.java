package io.github.ryancamus.clinic.schedule.enums;

/**
 * 预约状态。迁移只能通过 RegistrationMapper#casStatus 完成（带当前状态条件），并发下只有一个请求生效。
 * <pre>
 *   待支付 ──付款──▶ 已预约 ──就诊──▶ 已就诊
 *     │                │
 *     └─超时─▶ 已过期   └─取消─▶ 已取消（未缴费时）
 *     └─取消─▶ 已取消
 * </pre>
 * 待支付、已预约占用号源；已过期、已取消归还号源。
 */
public final class RegistrationStatus {

    public static final int PENDING_PAYMENT = 0;
    public static final int BOOKED = 1;
    public static final int VISITED = 2;
    public static final int CANCELLED = 3;
    public static final int EXPIRED = 4;

    private RegistrationStatus() {
    }

    public static boolean holdsSlot(int status) {
        return status == PENDING_PAYMENT || status == BOOKED;
    }
}
