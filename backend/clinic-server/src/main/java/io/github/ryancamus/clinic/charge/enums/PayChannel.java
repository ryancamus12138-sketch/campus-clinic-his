package io.github.ryancamus.clinic.charge.enums;

import java.util.Arrays;
import java.util.Optional;

/**
 * 收款方式。窗口当前可用的是现金和银行卡（POS）；校园卡、付款码（支付宝、微信）需接入对应平台后才会出现在收费界面。
 */
public enum PayChannel {

    CASH(1, "现金", false),
    CAMPUS_CARD(2, "校园卡", false),
    ALIPAY(3, "支付宝", true),
    WECHAT(4, "微信支付", true),
    /** 保留编码：医保个人账户。大学生多参加城乡居民医保，通常没有个人账户，未启用 */
    INSURANCE_ACCOUNT(5, "医保个人账户", false),
    /** 窗口 POS 刷卡：POS 机是独立终端，系统登记参考号，次日与银行对账单核对 */
    BANK_CARD(6, "银行卡", false);

    private final int code;
    private final String label;
    private final boolean thirdParty;

    PayChannel(int code, String label, boolean thirdParty) {
        this.code = code;
        this.label = label;
        this.thirdParty = thirdParty;
    }

    public int code() {
        return code;
    }

    public String label() {
        return label;
    }

    /** 经第三方支付渠道收取（窗口扫付款码），次日需与渠道账单对账 */
    public boolean thirdParty() {
        return thirdParty;
    }

    /** 窗口当面收取、计入收费员班次的方式：现金、校园卡、银行卡 */
    public boolean counter() {
        return this == CASH || this == CAMPUS_CARD || this == BANK_CARD;
    }

    public static boolean isCounter(int code) {
        return of(code).map(PayChannel::counter).orElse(false);
    }

    public static Optional<PayChannel> of(Integer code) {
        return Arrays.stream(values()).filter(c -> code != null && c.code == code).findFirst();
    }
}
