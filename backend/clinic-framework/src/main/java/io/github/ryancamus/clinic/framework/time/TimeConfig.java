package io.github.ryancamus.clinic.framework.time;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.ZoneId;

/**
 * 业务时钟。号源日期、预约超时、记账日期都按校医院所在时区计算，
 * 不依赖服务器或 CI 机器的默认时区；测试中可替换为固定时钟。
 */
@Configuration(proxyBeanMethods = false)
public class TimeConfig {

    @Bean
    public Clock clock(@Value("${clinic.timezone:Asia/Shanghai}") String zone) {
        return Clock.system(ZoneId.of(zone));
    }
}
