package io.github.ryancamus.clinic.system.config;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;

/**
 * 生产环境启动检查：拒绝开发环境的密钥和占位值。
 * <p>
 * 开发配置（application-dev.yml）里的数据密钥、索引密钥是公开的；登录令牌密钥在未注入时会回落到带
 * “dev-only / change-me” 字样的占位值。生产环境如果误用了它们，启动失败并指出是哪一项，而不是带着公开密钥运行。
 */
@Component
@Profile("prod")
public class ProductionSecretsGuard implements InitializingBean {

    static final List<String> CHECKED_KEYS = List.of("clinic.crypto.index-key", "clinic.crypto.keys.1");
    private static final List<String> PLACEHOLDER_MARKERS = List.of("dev-only", "test-only", "change-me");

    private final Environment env;
    private final Properties devValues;

    public ProductionSecretsGuard(Environment env) {
        this.env = env;
        this.devValues = loadDevValues();
    }

    @Override
    public void afterPropertiesSet() {
        List<String> problems = new ArrayList<>();
        String jwt = env.getProperty("clinic.security.jwt.secret", "");
        String lower = jwt.toLowerCase(Locale.ROOT);
        if (PLACEHOLDER_MARKERS.stream().anyMatch(lower::contains)) {
            problems.add("clinic.security.jwt.secret 仍是占位值");
        }
        for (String key : CHECKED_KEYS) {
            String value = env.getProperty(key, "");
            if (!value.isEmpty() && value.equals(devValue(key))) {
                problems.add(key + " 使用了开发环境的公开密钥");
            }
        }
        if (!problems.isEmpty()) {
            throw new IllegalStateException("生产环境密钥配置不安全：" + String.join("；", problems)
                    + "。请用 deploy/gen-secrets.sh 生成密钥并通过 Docker secrets 注入");
        }
    }

    /** YAML 中以数字为键的映射项，展开后可能是 keys.1 或 keys[1] */
    private String devValue(String key) {
        String v = devValues.getProperty(key);
        return v != null ? v : devValues.getProperty(key.replaceAll("\\.(\\d+)$", "[$1]"));
    }

    private static Properties loadDevValues() {
        ClassPathResource dev = new ClassPathResource("application-dev.yml");
        if (!dev.exists()) {
            return new Properties();
        }
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(dev);
        Properties p = yaml.getObject();
        return p == null ? new Properties() : p;
    }
}
