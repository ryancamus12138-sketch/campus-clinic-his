package io.github.ryancamus.clinic.system.service;

import io.github.ryancamus.clinic.common.exception.BizException;
import io.github.ryancamus.clinic.framework.security.ClinicSecurityProperties;
import io.github.ryancamus.clinic.system.enums.SystemErrorCode;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.util.Locale;
import java.util.Set;

/**
 * 口令复杂度：长度、字符类别、不含用户名、不在常见弱口令表中。
 * 临时口令（新建账号、重置）由系统随机生成，满足同样的规则。
 */
@Component
public class PasswordPolicy {

    /** 常见弱口令片段（小写比较）；完整的泄露口令库应在部署时按需扩充 */
    private static final Set<String> WEAK = Set.of("password", "passw0rd", "123456", "12345678", "qwerty", "abc123",
            "admin", "clinic", "111111", "000000", "iloveyou", "letmein", "welcome", "p@ssw0rd", "1qaz2wsx");
    private static final String UPPER = "ABCDEFGHJKLMNPQRSTUVWXYZ";
    private static final String LOWER = "abcdefghijkmnopqrstuvwxyz";
    private static final String DIGIT = "23456789";
    private static final String SYMBOL = "@#%&*+-=?";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final ClinicSecurityProperties.Password config;

    public PasswordPolicy(ClinicSecurityProperties properties) {
        this.config = properties.password();
    }

    public void check(String username, String password) {
        if (password == null || password.length() < config.minLength()) {
            throw weak("至少 " + config.minLength() + " 位");
        }
        int classes = 0;
        classes += password.chars().anyMatch(Character::isUpperCase) ? 1 : 0;
        classes += password.chars().anyMatch(Character::isLowerCase) ? 1 : 0;
        classes += password.chars().anyMatch(Character::isDigit) ? 1 : 0;
        classes += password.chars().anyMatch(c -> !Character.isLetterOrDigit(c)) ? 1 : 0;
        if (classes < config.minClasses()) {
            throw weak("大写字母、小写字母、数字、符号中至少包含 " + config.minClasses() + " 类");
        }
        String lower = password.toLowerCase(Locale.ROOT);
        if (username != null && username.length() >= 3 && lower.contains(username.toLowerCase(Locale.ROOT))) {
            throw weak("不能包含用户名");
        }
        if (WEAK.stream().anyMatch(lower::contains)) {
            throw weak("不能包含常见弱口令");
        }
    }

    /** 生成满足策略的临时口令：12 位，四类字符各至少一个，去掉易混淆的 0/O、1/l/I */
    public String generate() {
        String all = UPPER + LOWER + DIGIT + SYMBOL;
        char[] out = new char[12];
        out[0] = UPPER.charAt(RANDOM.nextInt(UPPER.length()));
        out[1] = LOWER.charAt(RANDOM.nextInt(LOWER.length()));
        out[2] = DIGIT.charAt(RANDOM.nextInt(DIGIT.length()));
        out[3] = SYMBOL.charAt(RANDOM.nextInt(SYMBOL.length()));
        for (int i = 4; i < out.length; i++) {
            out[i] = all.charAt(RANDOM.nextInt(all.length()));
        }
        for (int i = out.length - 1; i > 0; i--) {
            int j = RANDOM.nextInt(i + 1);
            char t = out[i];
            out[i] = out[j];
            out[j] = t;
        }
        return new String(out);
    }

    private static BizException weak(String reason) {
        return new BizException(SystemErrorCode.PASSWORD_TOO_WEAK, "口令不符合要求：" + reason);
    }
}
