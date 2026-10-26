package io.github.ryancamus.clinic.charge.enums;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;

/**
 * 收费单状态机。
 * <pre>
 *   待支付 ──付清──▶ 已支付 ──部分退费──▶ 部分退费 ──退完──▶ 已全退
 *     │                 └──────────全额退费──────────────────▲
 *     └──超时/作废──▶ 已关闭
 * </pre>
 * 收费、作废、超时关单之前先锁住收费单行，状态迁移再通过带状态条件的 UPDATE 完成（CAS），同一迁移只会成功一次。
 * 一张单的多种收款方式在一次窗口结算中一起登记，不存在“部分支付”的中间状态。
 */
public enum ChargeStatus {

    UNPAID(0, "待支付"),
    /** 数据库中保留的取值，当前版本不会产生 */
    PARTIAL_PAID(1, "部分支付"),
    PAID(2, "已支付"),
    PARTIAL_REFUNDED(3, "部分退费"),
    REFUNDED(4, "已全退"),
    CLOSED(5, "已关闭");

    private final int code;
    private final String label;

    ChargeStatus(int code, String label) {
        this.code = code;
        this.label = label;
    }

    public int code() {
        return code;
    }

    public String label() {
        return label;
    }

    public static ChargeStatus of(int code) {
        return Arrays.stream(values()).filter(s -> s.code == code).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("unknown charge status " + code));
    }

    /** 允许的下一状态（状态图的代码表示，由单元测试校验） */
    public Set<ChargeStatus> next() {
        return switch (this) {
            case UNPAID -> EnumSet.of(PAID, CLOSED);
            case PARTIAL_PAID -> EnumSet.of(PAID, CLOSED);
            case PAID -> EnumSet.of(PARTIAL_REFUNDED, REFUNDED);
            case PARTIAL_REFUNDED -> EnumSet.of(PARTIAL_REFUNDED, REFUNDED);
            case REFUNDED, CLOSED -> EnumSet.noneOf(ChargeStatus.class);
        };
    }

    public boolean canTransitTo(ChargeStatus target) {
        return next().contains(target);
    }

    /** 是否已经收到款项（含部分退费后的状态） */
    public boolean isSettled() {
        return this == PAID || this == PARTIAL_REFUNDED || this == REFUNDED;
    }
}
