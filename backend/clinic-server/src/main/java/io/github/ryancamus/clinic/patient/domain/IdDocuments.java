package io.github.ryancamus.clinic.patient.domain;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 证件号校验与规范化。
 * <p>
 * 校医院患者以学生为主，留学生持护照，港澳台学生持居住证或通行证，所以证件类型不止身份证。
 * 居民身份证和港澳台居民居住证遵循 GB 11643-1999（18 位，末位为 ISO 7064 MOD 11-2 校验码），
 * 可以从号码中取出出生日期和性别，与录入值交叉核对。
 */
public final class IdDocuments {

    /** 居民身份证 */
    public static final int RESIDENT_ID = 1;
    /** 护照 */
    public static final int PASSPORT = 2;
    /** 港澳台居民居住证 */
    public static final int HMT_RESIDENCE = 3;
    /** 港澳居民来往内地通行证、台湾居民来往大陆通行证 */
    public static final int HMT_PERMIT = 4;
    /** 外国人永久居留身份证 */
    public static final int FOREIGN_PERMANENT = 5;

    private static final int[] WEIGHTS = {7, 9, 10, 5, 8, 4, 2, 1, 6, 3, 7, 9, 10, 5, 8, 4, 2};
    private static final char[] CHECK = "10X98765432".toCharArray();
    private static final Pattern GB11643 = Pattern.compile("\\d{17}[\\dX]");
    private static final Pattern PASSPORT_NO = Pattern.compile("[A-Z0-9]{5,17}");
    private static final Pattern PERMIT_NO = Pattern.compile("[A-Z0-9]{8,11}");
    private static final Pattern MOBILE = Pattern.compile("1[3-9]\\d{9}");

    private IdDocuments() {
    }

    /** 去掉空格、统一大写；规范化后的值用于加密和计算盲索引，保证同一证件只对应一个索引 */
    public static String normalize(String value) {
        return value == null ? "" : value.replaceAll("\\s+", "").toUpperCase(Locale.ROOT);
    }

    /** 校验证件号；身份证类返回其中的出生日期和性别，其他证件返回空 */
    public static Optional<Embedded> validate(int idType, String normalized) {
        return switch (idType) {
            case RESIDENT_ID, HMT_RESIDENCE, FOREIGN_PERMANENT -> Optional.of(parseGb11643(normalized));
            case PASSPORT -> require(PASSPORT_NO.matcher(normalized).matches());
            case HMT_PERMIT -> require(PERMIT_NO.matcher(normalized).matches());
            default -> throw new IllegalArgumentException("unknown id type");
        };
    }

    public static boolean isMobile(String phone) {
        return phone != null && MOBILE.matcher(phone).matches();
    }

    private static Optional<Embedded> require(boolean valid) {
        if (!valid) {
            throw new InvalidIdException();
        }
        return Optional.empty();
    }

    private static Embedded parseGb11643(String no) {
        if (!GB11643.matcher(no).matches()) {
            throw new InvalidIdException();
        }
        int sum = 0;
        for (int i = 0; i < 17; i++) {
            sum += (no.charAt(i) - '0') * WEIGHTS[i];
        }
        if (CHECK[sum % 11] != no.charAt(17)) {
            throw new InvalidIdException();
        }
        LocalDate birth;
        try {
            birth = LocalDate.of(Integer.parseInt(no.substring(6, 10)), Integer.parseInt(no.substring(10, 12)),
                    Integer.parseInt(no.substring(12, 14)));
        } catch (DateTimeException e) {
            throw new InvalidIdException();
        }
        // 第 17 位奇数为男、偶数为女
        int gender = (no.charAt(16) - '0') % 2 == 1 ? 1 : 2;
        return new Embedded(birth, gender);
    }

    /** 身份证号中隐含的出生日期和性别（1 男 2 女） */
    public record Embedded(LocalDate birthDate, int gender) {
    }

    public static final class InvalidIdException extends RuntimeException {
        InvalidIdException() {
            super("invalid id number", null, false, false);
        }
    }
}
