package io.github.ryancamus.clinic.audit.web;

import io.github.ryancamus.clinic.audit.service.AuditService;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration(proxyBeanMethods = false)
public class AuditWebConfig implements WebMvcConfigurer {

    private final AuditService auditService;

    public AuditWebConfig(AuditService auditService) {
        this.auditService = auditService;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new AuditInterceptor(auditService::recordRequest))
                .addPathPatterns("/api/**")
                .excludePathPatterns("/api/auth/**");
    }
}
