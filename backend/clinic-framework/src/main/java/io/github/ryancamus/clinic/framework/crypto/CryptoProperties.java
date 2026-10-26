package io.github.ryancamus.clinic.framework.crypto;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.util.Map;

/**
 * 字段加密配置，前缀 clinic.crypto。
 * <p>
 * 生产环境密钥只能来自环境变量或 Docker secrets（/run/secrets 下的文件），不得写入配置文件和仓库。
 *
 * @param algorithm   SM4（国密，默认，满足商用密码应用安全性评估）或 AES（256 位）
 * @param keys        数据密钥，键为密钥编号（1–255），值为 Base64。SM4 为 16 字节，AES 为 32 字节。
 *                    更换密钥时新增一个编号并把 activeKeyId 指过去；仍有数据使用的旧密钥必须保留
 * @param activeKeyId 新数据使用的密钥编号
 * @param indexKey    盲索引密钥（Base64，至少 32 字节）。身份证号、手机号取值空间小，
 *                    不加密钥的哈希可被穷举还原，所以检索用的哈希必须带密钥。该密钥不随数据密钥轮换
 */
@ConfigurationProperties(prefix = "clinic.crypto")
public record CryptoProperties(
        @DefaultValue("SM4") Algorithm algorithm,
        @DefaultValue Map<Integer, String> keys,
        Integer activeKeyId,
        String indexKey) {

    public enum Algorithm {
        SM4, AES
    }
}
