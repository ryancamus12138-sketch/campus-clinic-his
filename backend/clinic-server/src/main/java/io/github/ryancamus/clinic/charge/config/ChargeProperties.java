package io.github.ryancamus.clinic.charge.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;

/**
 * 收费配置，前缀 clinic.charge。
 *
 * @param counterOrderTtl     收费处划价后多久未缴费自动作废
 * @param refundAutoApproveLimit 单笔退费的个人自付金额不超过该值时自动通过，超过的需财务审批
 * @param feePolicies         计费策略，按参保类型匹配；未匹配的按自费处理
 */
@ConfigurationProperties(prefix = "clinic.charge")
public record ChargeProperties(
        @DefaultValue("2h") Duration counterOrderTtl,
        @DefaultValue("200.00") BigDecimal refundAutoApproveLimit,
        @DefaultValue List<FeePolicyConfig> feePolicies) {

    /**
     * @param code           策略编码，写入收费单作为快照
     * @param name           策略名称
     * @param insuranceTypes 适用的参保类型
     * @param ratio          可报销项目的统筹支付比例，如 0.80
     */
    public record FeePolicyConfig(String code, String name, List<Integer> insuranceTypes, BigDecimal ratio) {
    }
}
