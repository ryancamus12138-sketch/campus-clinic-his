package io.github.ryancamus.clinic.charge.fee;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * 计费策略：可报销项目按 ratio 由医保统筹记账，其余个人自付。
 * <p>
 * 起付线、报销比例由各地医保政策和学校协议决定，在 {@code clinic.charge.fee-policies} 中按参保类型配置；
 * 例如某地大学生医保在校医院门诊统筹支付 80%，则 ratio = 0.80。不可报销项目（如体检、疫苗服务费）全部自付。
 * 这里只用于划价时估算；接入医保结算平台后以医保中心的结算结果为准。
 */
public record FeePolicy(String code, String name, BigDecimal ratio) {

    public static final FeePolicy SELF_PAY = new FeePolicy("SELF", "自费", BigDecimal.ZERO);

    public FeePolicy {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(ratio, "ratio");
        if (ratio.signum() < 0 || ratio.compareTo(BigDecimal.ONE) > 0) {
            throw new IllegalArgumentException("ratio must be within [0, 1]: " + ratio);
        }
        ratio = ratio.setScale(4, RoundingMode.UNNECESSARY);
    }
}
