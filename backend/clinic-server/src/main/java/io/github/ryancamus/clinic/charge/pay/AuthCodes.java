package io.github.ryancamus.clinic.charge.pay;

import io.github.ryancamus.clinic.charge.enums.PayChannel;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 按付款码的号段识别渠道，窗口扫码后无需收费员再选“微信”还是“支付宝”。
 * <ul>
 *   <li>微信付款码：18 位数字，以 10–15 开头（微信支付付款码支付开发文档）；</li>
 *   <li>支付宝付款码：16–24 位数字，以 25–30 开头（支付宝当面付开发文档）。</li>
 * </ul>
 */
public final class AuthCodes {

    private static final Pattern WECHAT = Pattern.compile("1[0-5]\\d{16}");
    private static final Pattern ALIPAY = Pattern.compile("(2[5-9]|30)\\d{14,22}");

    private AuthCodes() {
    }

    public static Optional<PayChannel> channelOf(String authCode) {
        if (authCode == null) {
            return Optional.empty();
        }
        String code = authCode.trim();
        if (WECHAT.matcher(code).matches()) {
            return Optional.of(PayChannel.WECHAT);
        }
        if (ALIPAY.matcher(code).matches()) {
            return Optional.of(PayChannel.ALIPAY);
        }
        return Optional.empty();
    }
}
