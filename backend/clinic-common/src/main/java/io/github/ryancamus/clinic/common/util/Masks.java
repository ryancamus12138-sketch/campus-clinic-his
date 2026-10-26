package io.github.ryancamus.clinic.common.util;

/**
 * 敏感信息脱敏显示。接口默认只返回脱敏值，完整值需要单独授权并留痕。
 */
public final class Masks {

    private Masks() {
    }

    /**
     * 证件号：保留首位和后 4 位，如 3*************1234；过短的整体打码。
     * <p>
     * 身份证号的第 7–14 位就是出生日期，界面上本来就显示出生日期，若再多保留前几位（地区码），
     * 剩下的未知位只有两三位，几乎等于明文，所以只留窗口核对常用的“后四位”。
     */
    public static String idNo(String value) {
        return keep(value, 1, 4);
    }

    /** 手机号：保留前 3 位和后 4 位，如 138****5678 */
    public static String phone(String value) {
        return keep(value, 3, 4);
    }

    private static String keep(String value, int head, int tail) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        if (value.length() <= head + tail) {
            return "*".repeat(value.length());
        }
        return value.substring(0, head) + "*".repeat(value.length() - head - tail)
                + value.substring(value.length() - tail);
    }
}
