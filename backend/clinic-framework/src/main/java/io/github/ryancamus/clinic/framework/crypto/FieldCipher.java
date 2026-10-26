package io.github.ryancamus.clinic.framework.crypto;

import org.bouncycastle.jce.provider.BouncyCastleProvider;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.Provider;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * 敏感字段加密与盲索引。
 * <p>
 * 密文格式：{@code 版本(1) | 密钥编号(1) | IV(12) | 密文 + GCM 标签(16)}。
 * <ul>
 *     <li>认证加密（GCM）：密文被改动一个字节都会解密失败，而不是解出错误的值。</li>
 *     <li>附加数据（AAD）绑定字段用途：把手机号的密文复制到身份证号列，解密同样失败。</li>
 *     <li>密钥编号写在密文里：新增数据密钥后，旧密钥加密的数据仍可解密（存量数据重新加密需另行处理，当前版本未提供）。</li>
 *     <li>盲索引：HMAC（SM4 模式下为 HMAC-SM3）用于唯一约束和精确检索，数据库中不出现可穷举的普通哈希。</li>
 * </ul>
 * BouncyCastle 以实例方式使用，不注册为全局 Provider，不影响 TLS 等其他组件的算法选择。
 */
public final class FieldCipher {

    private static final byte FORMAT_VERSION = 1;
    private static final int IV_LENGTH = 12;
    private static final int TAG_BITS = 128;
    private static final int HEADER_LENGTH = 2 + IV_LENGTH;
    private static final Provider BC = new BouncyCastleProvider();
    private static final SecureRandom RANDOM = new SecureRandom();

    private final CryptoProperties.Algorithm algorithm;
    private final Map<Integer, SecretKeySpec> keys = new TreeMap<>();
    private final int activeKeyId;
    private final SecretKeySpec indexKey;

    public FieldCipher(CryptoProperties properties) {
        this.algorithm = properties.algorithm();
        int keyLength = algorithm == CryptoProperties.Algorithm.SM4 ? 16 : 32;
        String keyAlgorithm = algorithm == CryptoProperties.Algorithm.SM4 ? "SM4" : "AES";
        properties.keys().forEach((id, base64) -> {
            if (id < 1 || id > 255) {
                throw new IllegalStateException("clinic.crypto.keys 的编号必须在 1–255 之间: " + id);
            }
            byte[] raw = decodeKey(base64, "clinic.crypto.keys." + id);
            if (raw.length != keyLength) {
                throw new IllegalStateException("clinic.crypto.keys." + id + " 长度应为 " + keyLength
                        + " 字节（" + algorithm + "），实际 " + raw.length);
            }
            keys.put(id, new SecretKeySpec(raw, keyAlgorithm));
        });
        if (properties.activeKeyId() == null || !keys.containsKey(properties.activeKeyId())) {
            throw new IllegalStateException("clinic.crypto.active-key-id 未配置或对应的密钥不存在");
        }
        this.activeKeyId = properties.activeKeyId();
        byte[] index = decodeKey(properties.indexKey(), "clinic.crypto.index-key");
        if (index.length < 32) {
            throw new IllegalStateException("clinic.crypto.index-key 至少 32 字节");
        }
        this.indexKey = new SecretKeySpec(index, macAlgorithm());
    }

    /**
     * 加密。
     *
     * @param purpose   字段用途，如 {@code patient.id_no}，作为附加数据参与认证
     * @param plaintext 明文
     */
    public byte[] encrypt(String purpose, String plaintext) {
        byte[] iv = new byte[IV_LENGTH];
        RANDOM.nextBytes(iv);
        try {
            Cipher cipher = cipher(Cipher.ENCRYPT_MODE, keys.get(activeKeyId), iv, purpose);
            byte[] body = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            return ByteBuffer.allocate(HEADER_LENGTH + body.length)
                    .put(FORMAT_VERSION).put((byte) activeKeyId).put(iv).put(body).array();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("加密失败", e);
        }
    }

    public String decrypt(String purpose, byte[] data) {
        if (data == null || data.length <= HEADER_LENGTH || data[0] != FORMAT_VERSION) {
            throw new IllegalArgumentException("密文格式不正确");
        }
        SecretKeySpec key = keys.get(keyId(data));
        if (key == null) {
            throw new IllegalStateException("缺少编号为 " + keyId(data) + " 的数据密钥，仍有数据使用它时不得删除");
        }
        byte[] iv = new byte[IV_LENGTH];
        System.arraycopy(data, 2, iv, 0, IV_LENGTH);
        try {
            Cipher cipher = cipher(Cipher.DECRYPT_MODE, key, iv, purpose);
            return new String(cipher.doFinal(data, HEADER_LENGTH, data.length - HEADER_LENGTH),
                    StandardCharsets.UTF_8);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("解密失败：密文被篡改、字段用途不符或密钥不匹配", e);
        }
    }

    /** 密文是否由非当前密钥加密（存量数据重新加密时用于判断） */
    public boolean needsRotation(byte[] data) {
        return keyId(data) != activeKeyId;
    }

    public int activeKeyId() {
        return activeKeyId;
    }

    /**
     * 盲索引：64 位十六进制。不同用途使用不同前缀，同一个值在不同字段上的索引互不相同，无法跨字段关联。
     *
     * @param purpose    字段用途
     * @param normalized 规范化后的明文（调用方负责去空格、统一大小写等）
     */
    public String blindIndex(String purpose, String normalized) {
        try {
            Mac mac = algorithm == CryptoProperties.Algorithm.SM4
                    ? Mac.getInstance(macAlgorithm(), BC)
                    : Mac.getInstance(macAlgorithm());
            mac.init(indexKey);
            byte[] digest = mac.doFinal((purpose + ':' + normalized).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("计算盲索引失败", e);
        }
    }

    private Cipher cipher(int mode, SecretKeySpec key, byte[] iv, String purpose) throws GeneralSecurityException {
        Cipher cipher = algorithm == CryptoProperties.Algorithm.SM4
                ? Cipher.getInstance("SM4/GCM/NoPadding", BC)
                : Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(mode, key, new GCMParameterSpec(TAG_BITS, iv));
        cipher.updateAAD(purpose.getBytes(StandardCharsets.UTF_8));
        return cipher;
    }

    private String macAlgorithm() {
        return algorithm == CryptoProperties.Algorithm.SM4 ? "HMACSM3" : "HmacSHA256";
    }

    private static int keyId(byte[] data) {
        return data[1] & 0xFF;
    }

    private static byte[] decodeKey(String base64, String name) {
        if (base64 == null || base64.isBlank()) {
            throw new IllegalStateException(name + " 未配置");
        }
        try {
            return Base64.getDecoder().decode(base64.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(name + " 不是合法的 Base64", e);
        }
    }
}
