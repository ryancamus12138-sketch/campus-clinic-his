package io.github.ryancamus.clinic.charge.enums;

/**
 * 退费单状态。
 * <pre>
 *   待审核 ──通过──▶ 退款中 ──渠道成功──▶ 成功
 *     │                │  ▲
 *     └──驳回──▶ 已驳回  └──渠道失败──▶ 失败（定时重试）
 * </pre>
 * 待审核、退款中、失败三种状态视为“未完结”，同一收费单同时最多一笔（uk_one_active_refund）。
 */
public final class RefundStatus {

    public static final int PENDING_APPROVAL = 0;
    public static final int REFUNDING = 1;
    public static final int SUCCESS = 2;
    public static final int FAILED = 3;
    public static final int REJECTED = 4;

    private RefundStatus() {
    }
}
