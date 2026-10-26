package io.github.ryancamus.clinic.system;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 已提交的版本化迁移不能修改：已经执行过的脚本一旦改写，现有数据库启动时校验失败，
 * 而且改写的内容不会在现有数据库上生效。变更一律新增一个版本。
 * <p>
 * {@code migration-lock.txt} 记录每个迁移脚本的 SHA-256。新增迁移时，把测试失败信息中给出的那一行追加到文件末尾。
 */
class MigrationLockTest {

    @Test
    void committedMigrationsAreNeverRewritten() throws Exception {
        Map<String, String> locked = new LinkedHashMap<>();
        try (InputStream in = getClass().getResourceAsStream("/migration-lock.txt")) {
            assertThat(in).as("migration-lock.txt").isNotNull();
            for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
                String l = line.strip();
                if (!l.isEmpty() && !l.startsWith("#")) {
                    String[] parts = l.split("\\s+");
                    locked.put(parts[0], parts[1]);
                }
            }
        }
        Map<String, String> actual = new TreeMap<>();
        for (String dir : new String[] {"migration", "seed-dev"}) {
            Path root = Path.of(getClass().getResource("/db/" + dir).toURI());
            try (Stream<Path> files = Files.list(root)) {
                for (Path f : files.filter(p -> p.getFileName().toString().endsWith(".sql")).toList()) {
                    actual.put(dir + "/" + f.getFileName(), sha256(f));
                }
            }
        }

        for (var e : locked.entrySet()) {
            assertThat(actual).as("已提交的迁移 %s 不能删除或改名", e.getKey()).containsKey(e.getKey());
            assertThat(actual.get(e.getKey()))
                    .as("已提交的迁移 %s 被修改了。请恢复原文件，改动写成一个新版本的迁移", e.getKey())
                    .isEqualTo(e.getValue());
        }
        for (var e : actual.entrySet()) {
            assertThat(locked).as("新迁移 %s 未登记。确认内容定稿后，把这一行追加到 migration-lock.txt：%n%s %s",
                    e.getKey(), e.getKey(), e.getValue()).containsKey(e.getKey());
        }
    }

    private static String sha256(Path f) throws IOException, NoSuchAlgorithmException {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(f)));
    }
}
