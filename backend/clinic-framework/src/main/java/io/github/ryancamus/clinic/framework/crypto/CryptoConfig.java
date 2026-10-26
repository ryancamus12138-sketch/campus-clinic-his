package io.github.ryancamus.clinic.framework.crypto;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 启动时校验密钥：缺失、长度不对、编号不存在都直接启动失败，而不是在第一次建档时才报错 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(CryptoProperties.class)
public class CryptoConfig {

    @Bean
    public FieldCipher fieldCipher(CryptoProperties properties) {
        return new FieldCipher(properties);
    }
}
