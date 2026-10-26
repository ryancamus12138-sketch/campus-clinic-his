package io.github.ryancamus.clinic.system.service;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 使用本系统的机构，前缀 clinic.org。名称显示在登录页、页面标题和票据打印抬头；
 * 校区、收费点、收费项目与价格在数据库中维护（见“基础数据”），不在这里配置。
 *
 * @param name 机构名称；未配置时为演示名称“实践大学校医院”（不对应任何真实学校），部署时用 CLINIC_ORG_NAME 覆盖
 */
@ConfigurationProperties(prefix = "clinic.org")
public record OrgProperties(@DefaultValue("实践大学校医院") String name) {
}
