package io.github.ryancamus.clinic.framework.job;

import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.redis.spring.RedisLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 定时任务：超时释放、库存对账、掉单补偿等。
 * <p>
 * 用 ShedLock（Redis 实现）保证多实例部署时同一任务同一时刻只在一个实例上运行。
 * 测试环境通过 clinic.jobs.enabled=false 关闭自动调度，由测试直接调用任务方法，结果可控。
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@EnableSchedulerLock(defaultLockAtMostFor = "PT2M")
@ConditionalOnProperty(prefix = "clinic.jobs", name = "enabled", havingValue = "true", matchIfMissing = true)
public class SchedulingConfig {

    @Bean
    public LockProvider lockProvider(RedisConnectionFactory connectionFactory) {
        return new RedisLockProvider(connectionFactory, "clinic");
    }
}
