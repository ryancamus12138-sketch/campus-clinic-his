package io.github.ryancamus.clinic.framework.crypto;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.Base64;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FieldCipherTest {

    private static final String K16_A = b64(16, 1);
    private static final String K16_B = b64(16, 2);
    private static final String K32_A = b64(32, 3);
    private static final String K32_B = b64(32, 4);
    private static final String INDEX = b64(32, 9);

    @ParameterizedTest
    @EnumSource(CryptoProperties.Algorithm.class)
    void roundTripsAndUsesRandomIv(CryptoProperties.Algorithm algorithm) {
        FieldCipher cipher = cipher(algorithm, 1);
        byte[] a = cipher.encrypt("patient.id_no", "310101200001011234");
        byte[] b = cipher.encrypt("patient.id_no", "310101200001011234");
        assertThat(a).isNotEqualTo(b);
        assertThat(cipher.decrypt("patient.id_no", a)).isEqualTo("310101200001011234");
        assertThat(cipher.decrypt("patient.id_no", b)).isEqualTo("310101200001011234");
        // 版本 + 密钥编号 + 12 字节 IV + 18 字节密文 + 16 字节标签
        assertThat(a).hasSize(2 + 12 + 18 + 16);
    }

    @ParameterizedTest
    @EnumSource(CryptoProperties.Algorithm.class)
    void detectsTamperingOfEveryByte(CryptoProperties.Algorithm algorithm) {
        FieldCipher cipher = cipher(algorithm, 1);
        byte[] data = cipher.encrypt("patient.phone", "13800138000");
        int detected = 0;
        for (int i = 2; i < data.length; i++) {
            byte[] copy = data.clone();
            copy[i] ^= 0x01;
            try {
                cipher.decrypt("patient.phone", copy);
            } catch (IllegalStateException e) {
                detected++;
            }
        }
        assertThat(detected).isEqualTo(data.length - 2);
    }

    @Test
    void ciphertextIsBoundToItsPurpose() {
        FieldCipher cipher = cipher(CryptoProperties.Algorithm.SM4, 1);
        byte[] phone = cipher.encrypt("patient.phone", "13800138000");
        assertThatThrownBy(() -> cipher.decrypt("patient.id_no", phone)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void oldCiphertextStaysReadableAfterRotation() {
        FieldCipher v1 = cipher(CryptoProperties.Algorithm.SM4, 1);
        FieldCipher v2 = cipher(CryptoProperties.Algorithm.SM4, 2);
        byte[] old = v1.encrypt("patient.id_no", "A1234567");
        assertThat(v2.needsRotation(old)).isTrue();
        assertThat(v2.decrypt("patient.id_no", old)).isEqualTo("A1234567");
        byte[] fresh = v2.encrypt("patient.id_no", "A1234567");
        assertThat(v2.needsRotation(fresh)).isFalse();
        assertThat(fresh[1]).isEqualTo((byte) 2);
    }

    @Test
    void missingKeyIsReportedNotSilentlyIgnored() {
        FieldCipher v2 = cipher(CryptoProperties.Algorithm.SM4, 2);
        FieldCipher onlyKey1 = new FieldCipher(new CryptoProperties(CryptoProperties.Algorithm.SM4,
                Map.of(1, K16_A), 1, INDEX));
        byte[] data = v2.encrypt("x", "y");
        assertThatThrownBy(() -> onlyKey1.decrypt("x", data)).hasMessageContaining("缺少编号为 2");
    }

    @Test
    void blindIndexIsDeterministicKeyedAndPurposeSeparated() {
        FieldCipher a = cipher(CryptoProperties.Algorithm.SM4, 1);
        FieldCipher b = new FieldCipher(new CryptoProperties(CryptoProperties.Algorithm.SM4,
                Map.of(1, K16_A), 1, b64(32, 10)));
        String idx = a.blindIndex("patient.phone", "13800138000");
        assertThat(idx).hasSize(64).isEqualTo(a.blindIndex("patient.phone", "13800138000"));
        assertThat(a.blindIndex("patient.id_no", "13800138000")).isNotEqualTo(idx);
        // 不同索引密钥得到不同结果：拿到数据库也无法用彩虹表或穷举还原
        assertThat(b.blindIndex("patient.phone", "13800138000")).isNotEqualTo(idx);
    }

    @Test
    void rejectsMisconfiguredKeysAtStartup() {
        assertThatThrownBy(() -> new FieldCipher(new CryptoProperties(CryptoProperties.Algorithm.SM4,
                Map.of(1, K32_A), 1, INDEX))).hasMessageContaining("长度应为 16");
        assertThatThrownBy(() -> new FieldCipher(new CryptoProperties(CryptoProperties.Algorithm.SM4,
                Map.of(1, K16_A), 2, INDEX))).hasMessageContaining("active-key-id");
        assertThatThrownBy(() -> new FieldCipher(new CryptoProperties(CryptoProperties.Algorithm.SM4,
                Map.of(1, K16_A), 1, b64(16, 1)))).hasMessageContaining("index-key");
        assertThatThrownBy(() -> new FieldCipher(new CryptoProperties(CryptoProperties.Algorithm.SM4,
                Map.of(1, ""), 1, INDEX))).hasMessageContaining("未配置");
    }

    private static FieldCipher cipher(CryptoProperties.Algorithm algorithm, int active) {
        Map<Integer, String> keys = algorithm == CryptoProperties.Algorithm.SM4
                ? Map.of(1, K16_A, 2, K16_B)
                : Map.of(1, K32_A, 2, K32_B);
        return new FieldCipher(new CryptoProperties(algorithm, keys, active, INDEX));
    }

    private static String b64(int length, int seed) {
        byte[] bytes = new byte[length];
        for (int i = 0; i < length; i++) {
            bytes[i] = (byte) (seed * 31 + i);
        }
        return Base64.getEncoder().encodeToString(bytes);
    }
}
