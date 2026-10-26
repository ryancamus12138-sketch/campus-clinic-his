package io.github.ryancamus.clinic.schedule;

import io.github.ryancamus.clinic.schedule.service.DevScheduleInitializer;
import io.github.ryancamus.clinic.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Profile;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 开发环境初始化只在 dev Profile 下注册：测试（test）和生产（prod）环境都不执行。
 */
@IntegrationTest
class DevScheduleInitializerScopeTest {

    @Autowired
    private ApplicationContext context;

    @Test
    void registeredOnlyUnderTheDevProfile() {
        Profile profile = DevScheduleInitializer.class.getAnnotation(Profile.class);
        assertThat(profile).isNotNull();
        assertThat(profile.value()).containsExactly("dev");
    }

    @Test
    void notPresentOutsideTheDevProfile() {
        assertThat(context.getEnvironment().getActiveProfiles()).doesNotContain("dev");
        assertThat(context.getBeanNamesForType(DevScheduleInitializer.class)).isEmpty();
    }
}
