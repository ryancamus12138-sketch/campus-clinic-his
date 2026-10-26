package io.github.ryancamus.clinic.charge.insurance.mock;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 医保测试替身的配置，前缀 clinic.insurance.mock，只在测试中使用（生产配置中没有该前缀）。
 *
 * @param failRate 结算、撤销请求按此概率失败（相当于医保专网中断），用于验证重试与补偿
 */
@ConfigurationProperties(prefix = "clinic.insurance.mock")
public record MockInsuranceProperties(@DefaultValue("0") double failRate) {
}
