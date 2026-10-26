package io.github.ryancamus.clinic.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import java.time.ZoneId;

@TestConfiguration(proxyBeanMethods = false)
public class TestContainersConfig {

    public static final String ROOT_PASSWORD = "root-test-password";
    public static final String APP_USER = "clinic_app";
    public static final String APP_PASSWORD = "app-test-password";
    public static final String MIGRATOR_PASSWORD = "migrator-test-password";

    /**
     * 与生产相同的初始化脚本（deploy/mysql/init）创建两个最小权限账号：
     * 应用以 clinic_app 连接，Flyway 以 clinic_migrator 迁移。测试因此同样受权限约束，
     * 例如应用账号改不了审计日志。
     */
    @Bean
    MySQLContainer mysqlContainer() {
        return new MySQLContainer(DockerImageName.parse("mysql:8.4"))
                .withDatabaseName("clinic")
                .withUsername("root")
                .withPassword(ROOT_PASSWORD)
                .withEnv("DB_APP_PASSWORD", APP_PASSWORD)
                .withEnv("DB_MIGRATOR_PASSWORD", MIGRATOR_PASSWORD)
                .withCopyFileToContainer(
                        MountableFile.forHostPath("../../deploy/mysql/init/01-users.sh", 0755),
                        "/docker-entrypoint-initdb.d/01-users.sh")
                .withCommand("--character-set-server=utf8mb4", "--collation-server=utf8mb4_0900_ai_ci",
                        "--default-time-zone=+08:00")
                .withReuse(true);
    }

    @Bean
    DynamicPropertyRegistrar mysqlProperties(MySQLContainer mysql) {
        return registry -> {
            registry.add("spring.datasource.url", () -> "jdbc:mysql://" + mysql.getHost() + ":"
                    + mysql.getMappedPort(3306) + "/clinic?useSSL=false&allowPublicKeyRetrieval=true"
                    + "&serverTimezone=Asia/Shanghai&characterEncoding=utf8&rewriteBatchedStatements=true");
            registry.add("spring.datasource.username", () -> APP_USER);
            registry.add("spring.datasource.password", () -> APP_PASSWORD);
            registry.add("spring.flyway.user", () -> "clinic_migrator");
            registry.add("spring.flyway.password", () -> MIGRATOR_PASSWORD);
        };
    }

    /** 替换业务时钟，测试可以拨到任意日期 */
    @Bean
    @Primary
    MutableClock testClock() {
        return new MutableClock(ZoneId.of("Asia/Shanghai"));
    }

    @Bean
    @ServiceConnection(name = "redis")
    GenericContainer<?> redisContainer() {
        return new GenericContainer<>(DockerImageName.parse("redis:8.8-alpine"))
                .withExposedPorts(6379)
                .withReuse(true);
    }
}
