package io.github.ryancamus.clinic.charge.enums;

/**
 * 支付单状态：待支付 → 成功 / 失败 / 已关闭，终态不可再变。
 */
public final class PayStatus {

    public static final int PENDING = 0;
    public static final int SUCCESS = 1;
    public static final int FAILED = 2;
    public static final int CLOSED = 3;

    private PayStatus() {
    }
}
