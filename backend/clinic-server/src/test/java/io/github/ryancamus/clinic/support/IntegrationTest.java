package io.github.ryancamus.clinic.support;

import io.github.ryancamus.clinic.schedule.SlotFixtures;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 集成测试：启动完整应用，连接 Testcontainers 拉起的真实 MySQL 8.4 与 Redis 8。
 * 容器在同一 JVM 内复用，所有集成测试共享一套环境以缩短耗时。
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({TestContainersConfig.class, TestDoubles.class, SlotFixtures.class})
public @interface IntegrationTest {
}
