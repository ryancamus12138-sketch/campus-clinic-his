package io.github.ryancamus.clinic.charge.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ChargeProperties.class)
public class ChargeConfig {
}
