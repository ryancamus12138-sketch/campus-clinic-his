package io.github.ryancamus.clinic.schedule.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * 预约规则，前缀 clinic.booking。
 *
 * @param paymentTimeout 需缴费的预约在多久内未付款自动释放号源
 * @param examSchoolPaid 新生体检是否由学校统一付费。参照同类高校默认学生自费（需缴费后才算预约成功）
 */
@ConfigurationProperties(prefix = "clinic.booking")
public record BookingProperties(
        @DefaultValue("15m") Duration paymentTimeout,
        @DefaultValue("false") boolean examSchoolPaid) {
}
