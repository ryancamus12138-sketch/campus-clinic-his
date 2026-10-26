package io.github.ryancamus.clinic.charge.insurance.mock;

import io.github.ryancamus.clinic.charge.fee.FeeCalculator.Breakdown;
import io.github.ryancamus.clinic.charge.fee.FeeCalculator.FeeLine;
import io.github.ryancamus.clinic.charge.fee.FeeCalculator;
import io.github.ryancamus.clinic.charge.fee.FeePolicyResolver;
import io.github.ryancamus.clinic.charge.insurance.InsuranceGateway;
import io.github.ryancamus.clinic.common.money.Money;
import lombok.extern.slf4j.Slf4j;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 医保结算接口 {@link io.github.ryancamus.clinic.charge.insurance.InsuranceGateway} 的测试替身（Test Double）。
 * 只存在于 src/test，仅用于自动化测试；不是生产环境的外部平台适配器，不会进入生产构建。
 * <p>
 * 结算记录保存在内存中，分摊按测试配置的费用策略计算。按接口约定实现：结算必须带就诊凭证、同一业务号重复结算返回原结果、
 * 撤销幂等、可按概率失败、可下载对账明细；测试可通过 {@link #forget} 与 {@link #injectForeign} 制造对账差异。
 * 用于验证医保结算失败、撤销失败、结果未知和重结时的处理，不代表与医保平台的真实联调。
 */
@Slf4j
public class MockInsuranceGateway implements InsuranceGateway {

    private final FeePolicyResolver policies;
    private final Clock clock;
    private volatile double failRate;
    private final AtomicInteger loseNextResponses = new AtomicInteger();
    private final AtomicInteger failNextCancels = new AtomicInteger();
    private final Map<String, Settlement> settled = new ConcurrentHashMap<>();

    public MockInsuranceGateway(FeePolicyResolver policies, MockInsuranceProperties properties, Clock clock) {
        this.policies = policies;
        this.clock = clock;
        this.failRate = properties.failRate();
        log.warn("医保结算使用模拟实现：分摊按本地费用策略计算，未连接医保中心");
    }

    @Override
    public Breakdown preSettle(Insured insured, List<FeeLine> lines) {
        return FeeCalculator.calculate(lines, policies.resolve(insured.insuranceType()));
    }

    @Override
    public Settlement settle(String bizNo, Insured insured, List<FeeLine> lines) {
        Settlement existing = settled.get(bizNo);
        if (existing != null) {
            return existing;
        }
        maybeFail("结算");
        if (insured.insuranceType() != 0 && !CertType.valid(insured.certType())) {
            throw new InsuranceException("正式结算必须提供就诊凭证");
        }
        Breakdown b = preSettle(insured, lines);
        Settlement s = new Settlement(bizNo, "TEST-SETL" + UUID.randomUUID().toString().replace("-", "").substring(0, 20),
                b.totalAmount(), b.insuranceAmount(), b.selfAmount(), LocalDateTime.now(clock));
        Settlement prev = settled.putIfAbsent(bizNo, s);
        if (loseNextResponses.get() > 0 && loseNextResponses.getAndDecrement() > 0) {
            // 医保中心已结算，但响应在网络上丢失：调用方只看到超时
            throw new InsuranceException("医保测试替身：响应超时（已结算）");
        }
        return prev != null ? prev : s;
    }

    @Override
    public void cancel(String bizNo, String setlId) {
        if (!settled.containsKey(bizNo)) {
            return;
        }
        if (failNextCancels.get() > 0 && failNextCancels.getAndDecrement() > 0) {
            throw new InsuranceException("医保测试替身：撤销失败（网络中断）");
        }
        maybeFail("撤销");
        settled.remove(bizNo);
    }

    @Override
    public List<Settlement> statement(LocalDate date) {
        return settled.values().stream().filter(s -> s.settledAt().toLocalDate().equals(date)).toList();
    }

    /** 测试用：接下来 n 次撤销失败 */
    public void failNextCancels(int n) {
        failNextCancels.set(n);
    }

    /** 测试用：接下来 n 次结算在医保中心成功、但响应丢失 */
    public void loseNextResponses(int n) {
        loseNextResponses.set(n);
    }

    public boolean hasSettlement(String bizNo) {
        return settled.containsKey(bizNo);
    }

    public void setFailRate(double failRate) {
        this.failRate = failRate;
    }

    /** 测试用：医保中心丢失一条结算记录（对账应识别为短款） */
    public void forget(String bizNo) {
        settled.remove(bizNo);
    }

    /** 测试用：医保中心多出一条本系统没有的结算（对账应识别为长款） */
    public void injectForeign(String bizNo, Money fundPay, LocalDateTime at) {
        settled.put(bizNo, new Settlement(bizNo, "SETLX" + bizNo, fundPay, fundPay, Money.ZERO, at));
    }

    private void maybeFail(String action) {
        if (failRate > 0 && ThreadLocalRandom.current().nextDouble() < failRate) {
            throw new InsuranceException("医保测试替身：" + action + "失败（网络中断）");
        }
    }
}
