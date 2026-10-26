package io.github.ryancamus.clinic.system.config;

import io.github.ryancamus.clinic.system.service.OrgProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(OrgProperties.class)
public class SystemConfig {
}
