package io.github.ryancamus.clinic.support;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.mysql.MySQLContainer;

/**
 * 以 root 连接测试库：模拟拥有数据库管理员权限的攻击者，或者做应用账号无权做的检查。
 */
public final class RootJdbc {

    private RootJdbc() {
    }

    public static JdbcTemplate of(MySQLContainer mysql) {
        return new JdbcTemplate(new DriverManagerDataSource("jdbc:mysql://" + mysql.getHost() + ":"
                + mysql.getMappedPort(3306) + "/clinic?useSSL=false&allowPublicKeyRetrieval=true"
                + "&serverTimezone=Asia/Shanghai", "root", TestContainersConfig.ROOT_PASSWORD));
    }
}
