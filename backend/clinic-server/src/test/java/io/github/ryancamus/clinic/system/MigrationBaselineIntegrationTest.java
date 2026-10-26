package io.github.ryancamus.clinic.system;

import io.github.ryancamus.clinic.support.IntegrationTest;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 迁移校验：应用启动时使用 Flyway 的标准校验，任何 checksum 不一致都会被发现，不会被静默接受。
 * <p>
 * 基线之前建立的开发数据库，由运维脚本 {@code deploy/mysql/maintenance/realign-pre-baseline-checksums.sql}
 * 手动对齐。这里验证脚本中的校验值正确、只修改完全匹配的行。
 * 移除条件：发布第一个正式版本时，与该脚本一起删除本测试。
 */
@IntegrationTest
class MigrationBaselineIntegrationTest {

    private static final String TABLE = "flyway_schema_history";
    private static final Path SCRIPT = Path.of("../../deploy/mysql/maintenance/realign-pre-baseline-checksums.sql");
    private static final Pattern UPDATE = Pattern.compile(
            "UPDATE flyway_schema_history SET checksum = (-?\\d+)\\s+WHERE version = '(\\d+)'\\s+AND checksum = (-?\\d+)");

    @Autowired
    private Flyway flyway;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private ApplicationContext context;

    private record Realign(String sql, String version, int current, int legacy) {
    }

    @Test
    void startupUsesStandardFlywayValidation() {
        // 没有自定义迁移策略，启动时执行的就是 Flyway 默认的“校验后迁移”
        assertThat(context.getBeanNamesForType(FlywayMigrationStrategy.class)).isEmpty();
        assertThat(flyway.getConfiguration().isValidateOnMigrate()).isTrue();
        assertThat(flyway.validateWithResult().validationSuccessful).isTrue();
    }

    @Test
    void aTamperedChecksumIsReportedNotAccepted() {
        Realign v2 = script().getFirst();
        try {
            setChecksum(v2.version(), 12345);
            assertThat(flyway.validateWithResult().validationSuccessful).isFalse();
        } finally {
            setChecksum(v2.version(), v2.current());
        }
    }

    @Test
    void theOpsScriptHoldsTheChecksumsFlywayRecordsForTheCurrentFiles() {
        List<Realign> rows = script();
        assertThat(rows).extracting(Realign::version).containsExactly("2", "6", "13", "20", "1001", "1002");
        for (Realign r : rows) {
            assertThat(checksum(r.version())).as("V%s", r.version()).isEqualTo(r.current());
        }
    }

    @Test
    void theOpsScriptOnlyRealignsTheExactPreBaselineChecksum() {
        Realign v2 = script().getFirst();
        try {
            // 基线之前建立的库：对齐为当前值，再执行一次没有变化
            setChecksum(v2.version(), v2.legacy());
            assertThat(flyway.validateWithResult().validationSuccessful).isFalse();
            assertThat(jdbc.update(v2.sql())).isEqualTo(1);
            assertThat(checksum(v2.version())).isEqualTo(v2.current());
            assertThat(jdbc.update(v2.sql())).isZero();
            assertThat(flyway.validateWithResult().validationSuccessful).isTrue();

            // 其他任何不一致不处理，仍由 Flyway 校验报错
            setChecksum(v2.version(), 12345);
            assertThat(jdbc.update(v2.sql())).isZero();
            assertThat(checksum(v2.version())).isEqualTo(12345);
        } finally {
            setChecksum(v2.version(), v2.current());
        }
    }

    private static List<Realign> script() {
        try {
            String text = Files.readString(SCRIPT, StandardCharsets.UTF_8);
            List<Realign> rows = new ArrayList<>();
            for (String line : text.split("\n")) {
                Matcher m = UPDATE.matcher(line);
                if (m.find()) {
                    rows.add(new Realign(line.strip().replaceAll(";$", ""), m.group(2), Integer.parseInt(m.group(1)),
                            Integer.parseInt(m.group(3))));
                }
            }
            return rows;
        } catch (IOException e) {
            throw new IllegalStateException("读取运维脚本失败: " + SCRIPT.toAbsolutePath(), e);
        }
    }

    private Integer checksum(String version) {
        return jdbc.queryForObject("SELECT checksum FROM " + TABLE + " WHERE version = ?", Integer.class, version);
    }

    private void setChecksum(String version, int value) {
        jdbc.update("UPDATE " + TABLE + " SET checksum = ? WHERE version = ?", value, version);
    }
}
