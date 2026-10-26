package io.github.ryancamus.clinic.charge.fee;

import io.github.ryancamus.clinic.charge.config.ChargeProperties;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * 按患者参保类型选择计费策略。策略来自配置，报销比例调整时改配置即可，
 * 已生成的收费单保存了策略编码和每行比例的快照，不受影响。
 */
@Component
public class FeePolicyResolver {

    private final Map<Integer, FeePolicy> byInsuranceType = new HashMap<>();

    public FeePolicyResolver(ChargeProperties properties) {
        for (ChargeProperties.FeePolicyConfig c : properties.feePolicies()) {
            FeePolicy policy = new FeePolicy(c.code(), c.name(), c.ratio());
            for (Integer type : c.insuranceTypes()) {
                if (byInsuranceType.put(type, policy) != null) {
                    throw new IllegalStateException("参保类型 " + type + " 配置了多个计费策略");
                }
            }
        }
    }

    public FeePolicy resolve(int insuranceType) {
        return byInsuranceType.getOrDefault(insuranceType, FeePolicy.SELF_PAY);
    }
}
