package io.github.ryancamus.clinic;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 校医院门诊收费系统启动类。
 * <p>
 * 采用模块化单体：一个应用、一个业务库，按业务分包（system、patient、schedule、clinic、charge、
 * pharmacy、finance、event、audit），模块之间只通过 api 包里的接口或领域事件交互，由 ArchUnit 测试约束。
 */
@SpringBootApplication
public class ClinicApplication {

    public static void main(String[] args) {
        SpringApplication.run(ClinicApplication.class, args);
    }
}
