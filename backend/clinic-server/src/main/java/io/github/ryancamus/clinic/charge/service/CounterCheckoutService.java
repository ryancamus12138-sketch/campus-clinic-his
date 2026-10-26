package io.github.ryancamus.clinic.charge.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import io.github.ryancamus.clinic.charge.api.ChargePaidEvent;
import io.github.ryancamus.clinic.charge.campuscard.CampusCardGateway.CampusCardException;
import io.github.ryancamus.clinic.charge.campuscard.CampusCardGateway;
import io.github.ryancamus.clinic.charge.domain.dto.CheckoutDTO.Method;
import io.github.ryancamus.clinic.charge.domain.dto.CheckoutDTO.Tender;
import io.github.ryancamus.clinic.charge.domain.dto.CheckoutDTO;
import io.github.ryancamus.clinic.charge.domain.dto.TenderVerifyDTO;
import io.github.ryancamus.clinic.charge.domain.entity.ChargeOrder;
import io.github.ryancamus.clinic.charge.domain.entity.ChargeOrderItem;
import io.github.ryancamus.clinic.charge.domain.entity.CounterCheckout;
import io.github.ryancamus.clinic.charge.domain.entity.CounterCheckoutTender;
import io.github.ryancamus.clinic.charge.domain.entity.InsuranceSettlement;
import io.github.ryancamus.clinic.charge.domain.entity.PayOrder;
import io.github.ryancamus.clinic.charge.domain.vo.CheckoutExceptionVO;
import io.github.ryancamus.clinic.charge.domain.vo.CheckoutResultVO;
import io.github.ryancamus.clinic.charge.domain.vo.PayResultVO;
import io.github.ryancamus.clinic.charge.domain.vo.PlatformStatusVO;
import io.github.ryancamus.clinic.charge.enums.ChargeErrorCode;
import io.github.ryancamus.clinic.charge.enums.ChargeStatus;
import io.github.ryancamus.clinic.charge.enums.PayChannel;
import io.github.ryancamus.clinic.charge.enums.PayStatus;
import io.github.ryancamus.clinic.charge.mapper.ChargeOrderItemMapper;
import io.github.ryancamus.clinic.charge.mapper.ChargeOrderMapper;
import io.github.ryancamus.clinic.charge.mapper.CounterCheckoutMapper;
import io.github.ryancamus.clinic.charge.mapper.CounterCheckoutTenderMapper;
import io.github.ryancamus.clinic.charge.mapper.PayOrderMapper;
import io.github.ryancamus.clinic.charge.pay.AuthCodes;
import io.github.ryancamus.clinic.charge.pay.PayChannelClient.ChannelTrade;
import io.github.ryancamus.clinic.charge.pay.PayChannelClient.TradeState;
import io.github.ryancamus.clinic.charge.pay.PayChannelClient;
import io.github.ryancamus.clinic.charge.pay.PayChannelRegistry;
import io.github.ryancamus.clinic.common.exception.BizException;
import io.github.ryancamus.clinic.common.exception.CommonErrorCode;
import io.github.ryancamus.clinic.common.money.Money;
import io.github.ryancamus.clinic.finance.api.FinanceShiftApi;
import io.github.ryancamus.clinic.finance.api.FundLedgerApi;
import io.github.ryancamus.clinic.framework.util.BizNoGenerator;
import io.github.ryancamus.clinic.system.api.OrgQueryApi;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 收费窗口结算。一张收费单的结算顺序与真实窗口一致：
 * <ol>
 *   <li>有医保报销的，先办理医保结算确认统筹金额（医保平台未接入时，登记收费员在医保结算终端上的结算结果）；</li>
 *   <li>自付部分按收费员录入的一种或多种方式收取：现金、校园卡、银行卡（POS）、付款码；</li>
 *   <li>全部收齐后在一个事务内登记支付单、标记收费单已付、逐笔记资金流水、发出“已付清”事件。</li>
 * </ol>
 * 任何一步失败，已经扣到的校园卡、付款码原路撤回，医保结算撤销；全部确认撤回的，收费单保持未付，收费员可换一种方式重收。
 * 以下情况转为“收款异常”（待财务处理）：已收现金（是否当面退还只能由人确认，系统不能自行认定）、
 * 银行卡（POS 机独立于系统，撤不了）、撤回或医保撤销失败、结果未知、程序中途中断。
 * 在财务逐笔核实并登记处理结果之前，这张收费单不能再收费或作废，避免重复收费。
 */
@Slf4j
@Service
public class CounterCheckoutService {

    private static final String PAY_NO_PREFIX = "P";
    private static final Pattern CLIENT_KEY = Pattern.compile("[A-Za-z0-9-]{8,40}");
    private static final Pattern POS_REF = Pattern.compile("[A-Za-z0-9]{6,32}");

    private final ChargeOrderMapper orderMapper;
    private final ChargeOrderItemMapper itemMapper;
    private final PayOrderMapper payOrderMapper;
    private final PayChannelRegistry channels;
    private final ObjectProvider<CampusCardGateway> campusCard;
    private final InsuranceSettlementService insurance;
    private final CounterCheckoutMapper checkoutMapper;
    private final CounterCheckoutTenderMapper tenderMapper;
    private final FundLedgerApi fundLedgerApi;
    private final FinanceShiftApi shiftApi;
    private final ApplicationEventPublisher events;
    private final ChargeOutbox chargeOutbox;
    private final MeterRegistry meterRegistry;
    private final Clock clock;
    private final TransactionTemplate readCommittedTx;
    private final ChargeOrderGuard orderGuard;
    private final OrgQueryApi orgQueryApi;

    public CounterCheckoutService(ChargeOrderMapper orderMapper, ChargeOrderItemMapper itemMapper,
                                  PayOrderMapper payOrderMapper,
                                  PayChannelRegistry channels, ObjectProvider<CampusCardGateway> campusCard,
                                  InsuranceSettlementService insurance, CounterCheckoutMapper checkoutMapper,
                                  CounterCheckoutTenderMapper tenderMapper, FundLedgerApi fundLedgerApi,
                                  FinanceShiftApi shiftApi, ApplicationEventPublisher events, ChargeOutbox chargeOutbox,
                                  MeterRegistry meterRegistry, Clock clock, PlatformTransactionManager txManager,
                                  ChargeOrderGuard orderGuard, OrgQueryApi orgQueryApi) {
        this.orderGuard = orderGuard;
        this.orgQueryApi = orgQueryApi;
        this.orderMapper = orderMapper;
        this.itemMapper = itemMapper;
        this.payOrderMapper = payOrderMapper;
        this.channels = channels;
        this.campusCard = campusCard;
        this.insurance = insurance;
        this.checkoutMapper = checkoutMapper;
        this.tenderMapper = tenderMapper;
        this.fundLedgerApi = fundLedgerApi;
        this.shiftApi = shiftApi;
        this.events = events;
        this.chargeOutbox = chargeOutbox;
        this.meterRegistry = meterRegistry;
        this.clock = clock;
        // 读已提交：条件更新返回 0 行后，要能读到其他事务刚提交的状态
        this.readCommittedTx = new TransactionTemplate(txManager);
        this.readCommittedTx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    /**
     * @param clientKey 收费员客户端生成的幂等键：网络重试、重复点击都返回第一次的结果，不会重复收款
     */
    public CheckoutResultVO checkout(Long chargeOrderId, CheckoutDTO dto, String clientKey, Long cashierId) {
        if (clientKey == null || !CLIENT_KEY.matcher(clientKey).matches()) {
            throw new BizException(CommonErrorCode.BAD_REQUEST, "请求头 Idempotency-Key 缺失或格式不正确");
        }
        String idemKey = "C" + cashierId + ":" + clientKey;
        ChargeOrder order = orderMapper.selectById(chargeOrderId);
        BizException.throwIf(order == null, ChargeErrorCode.ORDER_NOT_FOUND);
        PayOrder existing = payOrderMapper.selectByIdempotencyKey(idemKey);
        if (existing != null) {
            BizException.throwIf(!existing.getChargeOrderId().equals(order.getId()),
                    ChargeErrorCode.IDEMPOTENCY_KEY_REUSED);
            return replay(order, idemKey, dto);
        }
        LocalDateTime now = LocalDateTime.now(clock);
        checkPayable(order, now);
        List<Planned> plan = plan(order, dto.tenders());
        // 先锁单再收钱：同一张收费单同时只能有一个窗口在结算，另一个窗口直接被拒绝，不会去扣卡或收现金
        CounterCheckout lock = lock(order, cashierId, idemKey);

        InsuranceSettlement settlement = null;
        List<PayOrder> collected = new ArrayList<>();
        try {
            // 窗口收取的款项计入收费员当班；没有当班记录时自动开班
            Long shiftId = shiftApi.ensureOpenShift(cashierId, order.getCampusId());
            if (!order.getInsuranceAmount().isZero()) {
                settlement = insurance.settleAtCounter(order, dto.certType(), dto.certNo(), dto.insurance(),
                        cashierId);
            }
            for (int i = 0; i < plan.size(); i++) {
                Planned p = plan.get(i);
                PayOrder pay = newPayOrder(order, p, i == 0 ? idemKey : idemKey + "#" + (i + 1), now);
                // 先登记“收取中”再收款：平台超时、程序中断时也知道这笔可能扣过款，撤回或人工核对都有据可查
                tenderMapper.insert(attempt(lock, i + 1, pay));
                collected.add(pay);
                collect(order, p, pay);
                tenderMapper.mark(pay.getPayNo(), CounterCheckoutTender.COLLECTED, pay.getChannelTradeNo(),
                        pay.getTenderRef());
            }
            readCommittedTx.executeWithoutResult(tx -> {
                record(order, collected, cashierId, shiftId, now);
                if (checkoutMapper.casStatus(lock.getId(), CounterCheckout.IN_PROGRESS, CounterCheckout.COMPLETED,
                        null) == 0) {
                    throw new IllegalStateException("结算记录已被标记为中断 checkoutId=" + lock.getId());
                }
            });
        } catch (RuntimeException e) {
            Undo undo = undo(collected);
            // 医保结算（包括结果未知的“结算中”）必须确认已撤销，才能把这次结算视为已撤回
            boolean insuranceClean = order.getInsuranceAmount().isZero() || insurance.cancelOutstanding(order.getId());
            boolean clean = undo.clean() && insuranceClean;
            List<String> pending = new ArrayList<>();
            if (undo.cashPending()) {
                pending.add("已收现金须当面退还患者");
            }
            if (undo.othersPending()) {
                pending.add("银行卡等已收款项须人工撤销（如在 POS 机上撤销当笔交易）");
            }
            if (!insuranceClean) {
                pending.add("医保结算未能撤销，须重试撤销或在医保端手工撤销");
            }
            String why = reason(e);
            checkoutMapper.casStatus(lock.getId(), CounterCheckout.IN_PROGRESS,
                    clean ? CounterCheckout.REVERTED : CounterCheckout.NEEDS_ATTENTION,
                    clean ? why : trim(String.join("；", pending) + "。原因：" + why));
            if (e instanceof DuplicateKeyException && clean) {
                // 同一个幂等键的并发请求，以先完成的为准
                PayOrder byKey = payOrderMapper.selectByIdempotencyKey(idemKey);
                if (byKey != null) {
                    return replay(order, idemKey, dto);
                }
                throw new BizException(ChargeErrorCode.PAY_IN_PROGRESS);
            }
            BizException biz = e instanceof BizException b ? b : null;
            String base = biz != null ? biz.getMessage() : ChargeErrorCode.TENDER_FAILED.message();
            if (!clean) {
                throw new BizException(biz != null ? biz.getErrorCode() : ChargeErrorCode.TENDER_FAILED,
                        base + "。注意：" + String.join("；", pending)
                                + "。已转为收款异常，财务逐笔核实前该收费单不能再收费或作废");
            }
            if (collected.isEmpty() && order.getInsuranceAmount().isZero()) {
                throw e;
            }
            throw new BizException(biz != null ? biz.getErrorCode() : ChargeErrorCode.TENDER_FAILED,
                    base + "。本次已收取的款项和医保结算均已撤销，可重新收费");
        }
        return result(order, settlement, collected, changeOf(plan));
    }

    /**
     * 只收现金的简便入口，等同于只有一行现金的窗口结算；有医保报销的同样需要医保结算（或登记）。
     */
    public PayResultVO cashPay(Long chargeOrderId, Money received, String clientKey, Long cashierId) {
        ChargeOrder order = orderMapper.selectById(chargeOrderId);
        BizException.throwIf(order == null, ChargeErrorCode.ORDER_NOT_FOUND);
        CheckoutResultVO r = checkout(chargeOrderId, new CheckoutDTO(
                List.of(Tender.cash(order.getPayableAmount(), received)), null, null), clientKey, cashierId);
        PayOrder pay = payOrderMapper.selectByPayNo(r.tenders().getFirst().payNo());
        return new PayResultVO(pay.getPayNo(), r.chargeNo(), pay.getChannel(), pay.getAmount(), pay.getStatus(),
                r.change());
    }

    static void checkPayable(ChargeOrder order, LocalDateTime now) {
        ChargeStatus status = ChargeStatus.of(order.getStatus());
        BizException.throwIf(status.isSettled(), ChargeErrorCode.ORDER_ALREADY_PAID);
        BizException.throwIf(status != ChargeStatus.UNPAID, ChargeErrorCode.ORDER_NOT_PAYABLE);
        BizException.throwIf(!order.getExpireTime().isAfter(now), ChargeErrorCode.ORDER_EXPIRED);
    }

    /**
     * 中断的结算：进行中超过 {@code olderThan} 仍未结束（程序在收款途中重启），转为待人工处理并释放锁。
     * 由定时任务调用。
     */
    public int markStale(Duration olderThan, int limit) {
        int n = 0;
        for (CounterCheckout c : checkoutMapper.selectStale(LocalDateTime.now(clock).minus(olderThan), limit)) {
            n += checkoutMapper.casStatus(c.getId(), CounterCheckout.IN_PROGRESS, CounterCheckout.NEEDS_ATTENTION,
                    "结算中途中断，请核对各收款方式是否已扣款");
        }
        if (n > 0) {
            meterRegistry.counter("clinic.checkout.undo.failed").increment(n);
            log.warn("中断的窗口结算 {} 笔已转为待人工处理", n);
        }
        return n;
    }

    /**
     * 收款异常（撤回失败、医保撤销失败、中途中断），供财务核对。
     *
     * @param resolved false 待处理；true 已处理的记录（含处理人、时间、说明），最近 200 条
     */
    public List<CheckoutExceptionVO> exceptions(boolean resolved) {
        List<CounterCheckout> rows = checkoutMapper.selectList(Wrappers.<CounterCheckout>lambdaQuery()
                .eq(CounterCheckout::getStatus, resolved ? CounterCheckout.RESOLVED : CounterCheckout.NEEDS_ATTENTION)
                .orderBy(true, !resolved, CounterCheckout::getId)
                .last("LIMIT 200"));
        Map<Long, List<CounterCheckoutTender>> tenders = new HashMap<>();
        rows.forEach(c -> tenders.put(c.getId(), tenderMapper.selectByCheckout(c.getId())));
        List<Long> userIds = Stream.concat(
                        rows.stream().flatMap(c -> Stream.of(c.getCashierId(), c.getResolverId())),
                        tenders.values().stream().flatMap(List::stream).map(CounterCheckoutTender::getVerifyBy))
                .filter(Objects::nonNull).distinct().toList();
        Map<Long, String> names = userIds.isEmpty() ? Map.of() : orgQueryApi.userNicknames(userIds);
        return rows.stream().map(c -> new CheckoutExceptionVO(c.getId(),
                orderMapper.selectById(c.getChargeOrderId()).getChargeNo(), c.getCashierId(),
                names.get(c.getCashierId()), c.getStatus(), c.getFailReason(), c.getCreateTime(),
                tenders.get(c.getId()).stream()
                        .map(t -> new CheckoutExceptionVO.Tender(t.getId(), t.getPayNo(), t.getChannel(),
                                t.getAmount(), t.getStatus(), t.getChannelTradeNo(), t.getTenderRef(),
                                t.needsVerification(), t.getVerifyBy(),
                                t.getVerifyBy() == null ? null : names.get(t.getVerifyBy()), t.getVerifyTime(),
                                t.getVerifyRef(), t.getVerifyNote()))
                        .toList(),
                insurance.listByOrder(c.getChargeOrderId()).stream()
                        .map(r -> new CheckoutExceptionVO.Insurance(r.getBizNo(), r.getSetlId(), r.getSource(),
                                r.getStatus(), r.getFundPay(), r.getCancelNote()))
                        .toList(),
                c.getResolverId(), c.getResolverId() == null ? null : names.get(c.getResolverId()),
                c.getResolveTime(), c.getResolveNote())).toList();
    }

    /**
     * 重试撤销这张收费单上未撤销的医保结算（医保中心恢复后）。
     *
     * @return 是否已全部撤销
     */
    public boolean retryInsuranceCancel(Long checkoutId) {
        CounterCheckout c = requireAttention(checkoutId);
        return insurance.cancelOutstanding(c.getChargeOrderId());
    }

    /**
     * 财务逐笔核实一笔可能已扣款的收款：款项已退还患者，或核实实际未扣款。
     * <p>
     * 这些款项在结算失败时没有登记为收款，不计入任何班次；核实只记录“钱去了哪里”，由谁、凭什么凭证确认。
     * 银行卡、校园卡、付款码必须填写凭证号（POS 撤销/退货参考号、平台流水号），现金须写明说明。
     * 不能由当笔收费的收费员本人核实。
     */
    public void verifyTender(Long checkoutId, Long tenderId, TenderVerifyDTO dto, Long verifierId) {
        CounterCheckout c = requireAttention(checkoutId);
        BizException.throwIf(verifierId.equals(c.getCashierId()), ChargeErrorCode.SAME_PERSON);
        CounterCheckoutTender t = tenderMapper.selectById(tenderId);
        BizException.throwIf(t == null || !t.getCheckoutId().equals(checkoutId), ChargeErrorCode.CHECKOUT_NOT_FOUND);
        BizException.throwIf(!t.needsVerification(), ChargeErrorCode.CHECKOUT_STATE_CHANGED);
        String ref = blankToNull(dto.reference());
        String note = blankToNull(dto.note());
        if (t.getChannel() == PayChannel.CASH.code()) {
            BizException.throwIf(note == null, ChargeErrorCode.VERIFY_REFERENCE_REQUIRED, "现金请写明核实说明，如“已当面退还患者”");
        } else {
            BizException.throwIf(ref == null, ChargeErrorCode.VERIFY_REFERENCE_REQUIRED);
        }
        int status = dto.result() == TenderVerifyDTO.Result.RETURNED ? CounterCheckoutTender.VERIFIED_RETURNED
                : CounterCheckoutTender.VERIFIED_NOT_CHARGED;
        BizException.throwIf(tenderMapper.verify(tenderId, checkoutId, status, verifierId, LocalDateTime.now(clock), ref,
                note) == 0, ChargeErrorCode.CHECKOUT_STATE_CHANGED);
        log.info("收款异常逐笔核实 checkoutId={} payNo={} result={} verifier={}", checkoutId, t.getPayNo(),
                dto.result(), verifierId);
    }

    /**
     * 财务核对完毕，标记已处理，收费单解除锁定，可以重新收费或作废。
     * <ul>
     *   <li>可能已扣款的每一笔收款都已逐笔核实（{@link #verifyTender}），不能只凭一段说明解除；</li>
     *   <li>医保结算必须已撤销：先自动重试一次；仍失败时，只有财务确认“已在医保端手工撤销”才能处理；</li>
     *   <li>不能由当笔收费的收费员本人处理。</li>
     * </ul>
     *
     * @param insuranceCancelledManually 财务已在医保端（医保结算终端或医保经办机构）手工撤销
     */
    public void resolve(Long checkoutId, String note, boolean insuranceCancelledManually, Long resolverId) {
        CounterCheckout c = requireAttention(checkoutId);
        BizException.throwIf(resolverId.equals(c.getCashierId()), ChargeErrorCode.SAME_PERSON);
        long unverified = tenderMapper.selectByCheckout(checkoutId).stream()
                .filter(CounterCheckoutTender::needsVerification).count();
        BizException.throwIf(unverified > 0, ChargeErrorCode.TENDERS_NOT_VERIFIED,
                "还有 " + unverified + " 笔可能已扣款的收款没有逐笔核实，核实后才能解除");
        Long orderId = c.getChargeOrderId();
        if (!insurance.outstanding(orderId).isEmpty() && !insurance.cancelOutstanding(orderId)) {
            BizException.throwIf(!insuranceCancelledManually, ChargeErrorCode.INSURANCE_CANCEL_PENDING);
            insurance.markCancelledManually(orderId, resolverId, note);
        }
        BizException.throwIf(checkoutMapper.resolve(checkoutId, note, resolverId, LocalDateTime.now(clock)) == 0,
                ChargeErrorCode.CHECKOUT_STATE_CHANGED);
        log.info("收款异常已处理 checkoutId={} resolver={}", checkoutId, resolverId);
    }

    private CounterCheckout requireAttention(Long checkoutId) {
        CounterCheckout c = checkoutMapper.selectById(checkoutId);
        BizException.throwIf(c == null, ChargeErrorCode.CHECKOUT_NOT_FOUND);
        BizException.throwIf(c.getStatus() != CounterCheckout.NEEDS_ATTENTION, ChargeErrorCode.CHECKOUT_STATE_CHANGED);
        return c;
    }

    private CounterCheckout lock(ChargeOrder order, Long cashierId, String idemKey) {
        CounterCheckout c = new CounterCheckout();
        c.setChargeOrderId(order.getId());
        c.setCashierId(cashierId);
        c.setIdempotencyKey(idemKey);
        c.setStatus(CounterCheckout.IN_PROGRESS);
        try {
            // 锁住收费单行后判断并写入结算记录：与其他窗口、作废和超时关单互斥（见 ChargeOrderGuard）
            return orderGuard.withLock(order.getId(), locked -> {
                checkPayable(locked, LocalDateTime.now(clock));
                CounterCheckout blocking = orderGuard.blockingCheckout(order.getId());
                // 同一收费员的重复提交在等第一次的结果；别的窗口则提示正在收费或有待处理的异常
                BizException.throwIf(blocking != null && blocking.getStatus() == CounterCheckout.IN_PROGRESS
                        && idemKey.equals(blocking.getIdempotencyKey()), ChargeErrorCode.PAY_IN_PROGRESS);
                orderGuard.requireNoBlockingCheckout(order.getId());
                checkoutMapper.insert(c);
                return c;
            });
        } catch (DuplicateKeyException e) {
            // 唯一约束是第二道防线，正常情况下行锁已经挡住
            throw new BizException(ChargeErrorCode.CHECKOUT_IN_PROGRESS);
        }
    }

    private static CounterCheckoutTender attempt(CounterCheckout lock, int seq, PayOrder pay) {
        CounterCheckoutTender t = new CounterCheckoutTender();
        t.setCheckoutId(lock.getId());
        t.setSeq(seq);
        t.setPayNo(pay.getPayNo());
        t.setChannel(pay.getChannel());
        t.setAmount(pay.getAmount());
        t.setStatus(CounterCheckoutTender.COLLECTING);
        return t;
    }

    /** 写入收款异常的原因：业务错误照原文；系统错误只写概述，SQL 等细节留在后端日志里，不展示给操作员 */
    private static String reason(RuntimeException e) {
        if (e instanceof BizException) {
            return trim(e.getMessage());
        }
        log.error("窗口结算过程中出现系统错误", e);
        return "收款登记时系统出错（详见后端日志）";
    }

    private static String trim(String m) {
        return m.length() > 200 ? m.substring(0, 200) : m;
    }

    public PlatformStatusVO platformStatus() {
        return new PlatformStatusVO(insurance.connected(), campusCard.getIfAvailable() != null,
                channels.find(PayChannel.WECHAT.code()).isPresent() || channels.find(PayChannel.ALIPAY.code()).isPresent());
    }

    /**
     * 某校区当前可用的窗口收款方式：收费点开放的方式（数据库配置），且对应外部平台已接入
     * （现金、银行卡不依赖外部平台；校园卡需要一卡通，付款码需要统一支付平台）。
     *
     * @param campusId 为空时不按收费点过滤
     */
    public List<Method> availableMethods(Long campusId) {
        Set<String> configured = campusId == null ? null
                : orgQueryApi.defaultChargePoint(campusId).map(OrgQueryApi.ChargePointInfo::counterMethods).orElse(null);
        List<Method> methods = new ArrayList<>();
        for (Method m : Method.values()) {
            boolean connected = switch (m) {
                case CASH, BANK_CARD -> true;
                case CAMPUS_CARD -> campusCard.getIfAvailable() != null;
                case AUTH_CODE -> channels.find(PayChannel.WECHAT.code()).isPresent()
                        || channels.find(PayChannel.ALIPAY.code()).isPresent();
            };
            if (connected && (configured == null || configured.contains(m.name()))) {
                methods.add(m);
            }
        }
        return methods;
    }

    // ------------------------------------------------------------------ 校验

    private record Planned(Tender tender, PayChannel channel) {
    }

    private List<Planned> plan(ChargeOrder order, List<Tender> tenders) {
        List<Planned> plan = new ArrayList<>();
        List<Method> allowed = availableMethods(order.getCampusId());
        Money sum = Money.ZERO;
        int cash = 0;
        for (Tender t : tenders) {
            BizException.throwIf(t.amount().isZero(), ChargeErrorCode.TENDER_INVALID, "收款金额必须大于 0");
            BizException.throwIf(!allowed.contains(t.method()), ChargeErrorCode.CHANNEL_NOT_SUPPORTED,
                    "本收费点未开放该收款方式，或对应平台未接入");
            PayChannel channel = switch (t.method()) {
                case CASH -> {
                    cash++;
                    Money received = t.received() == null ? t.amount() : t.received();
                    BizException.throwIf(t.amount().greaterThan(received), ChargeErrorCode.INSUFFICIENT_CASH);
                    yield PayChannel.CASH;
                }
                case CAMPUS_CARD -> {
                    BizException.throwIf(t.cardNo() == null || t.cardNo().isBlank(), ChargeErrorCode.TENDER_INVALID,
                            "请刷校园卡或输入卡号");
                    BizException.throwIf(campusCard.getIfAvailable() == null, ChargeErrorCode.CHANNEL_NOT_SUPPORTED,
                            "校园卡收费未开通");
                    yield PayChannel.CAMPUS_CARD;
                }
                case BANK_CARD -> {
                    BizException.throwIf(t.reference() == null || !POS_REF.matcher(t.reference()).matches(),
                            ChargeErrorCode.TENDER_INVALID, "请录入 POS 小票上的参考号");
                    yield PayChannel.BANK_CARD;
                }
                case AUTH_CODE -> {
                    PayChannel c = AuthCodes.channelOf(t.authCode()).orElseThrow(() -> new BizException(
                            ChargeErrorCode.TENDER_INVALID, "无法识别的付款码，请让患者刷新后重扫"));
                    channels.require(c.code());
                    yield c;
                }
            };
            plan.add(new Planned(t, channel));
            sum = sum.add(t.amount());
        }
        BizException.throwIf(cash > 1, ChargeErrorCode.TENDER_INVALID, "现金只需录入一行");
        BizException.throwIf(!sum.equals(order.getPayableAmount()), ChargeErrorCode.TENDER_SUM_MISMATCH,
                "各收款方式合计 " + sum + " 元，应收 " + order.getPayableAmount() + " 元");
        return plan;
    }

    // ------------------------------------------------------------------ 收款与撤回

    /** 向外部平台收取一种方式的款项，成功后把渠道流水号、凭据写到支付单上（尚未入库） */
    private void collect(ChargeOrder order, Planned p, PayOrder pay) {
        switch (p.tender().method()) {
            case CASH -> {
            }
            case BANK_CARD -> pay.setTenderRef(p.tender().reference());
            case CAMPUS_CARD -> {
                try {
                    var debit = campusCard.getObject().debit(pay.getPayNo(), p.tender().cardNo().trim(),
                            pay.getAmount());
                    pay.setChannelTradeNo(debit.tradeNo());
                    pay.setTenderRef(mask(p.tender().cardNo().trim()));
                } catch (CampusCardException e) {
                    throw new BizException(ChargeErrorCode.TENDER_FAILED, "校园卡扣款失败：" + e.getMessage());
                } catch (RuntimeException e) {
                    log.warn("校园卡扣款结果未知 payNo={}", pay.getPayNo(), e);
                    throw new BizException(ChargeErrorCode.TENDER_FAILED, "校园卡平台暂不可用");
                }
            }
            case AUTH_CODE -> {
                PayChannelClient client = channels.require(p.channel().code());
                ChannelTrade trade = client.micropay(p.channel().code(), pay.getPayNo(), p.tender().authCode().trim(),
                        pay.getAmount(), "校医院门诊缴费 " + order.getChargeNo());
                if (trade.state() == TradeState.NOT_PAID) {
                    // 患者正在输密码：再查一次，仍未完成就撤单，请患者重新出示付款码
                    trade = client.query(pay.getPayNo());
                }
                if (trade.state() != TradeState.SUCCESS) {
                    client.close(pay.getPayNo());
                    throw new BizException(ChargeErrorCode.TENDER_FAILED, p.channel().label() + "付款码扣款未成功");
                }
                pay.setChannelTradeNo(trade.tradeNo());
            }
        }
    }

    /**
     * 撤回本次已收取的款项的结果。
     *
     * @param cashPending   有已收现金，是否已当面退还须人工确认
     * @param othersPending 有银行卡等款项撤不回或结果未知
     */
    private record Undo(boolean cashPending, boolean othersPending) {
        boolean clean() {
            return !cashPending && !othersPending;
        }
    }

    /**
     * 撤回本次已收取的款项（医保结算由调用方另行撤销）。现金不会被自动认定为已退还：
     * 系统只知道收费员录入了现金，退没退给患者要由人核实，所以登记为“现金待确认退还”，转财务逐笔核实。
     */
    private Undo undo(List<PayOrder> collected) {
        boolean cash = false;
        boolean others = false;
        for (PayOrder pay : collected) {
            if (pay.getChannel() == PayChannel.CASH.code()) {
                cash = true;
                tenderMapper.mark(pay.getPayNo(), CounterCheckoutTender.CASH_RETURN_UNCONFIRMED, null, null);
                continue;
            }
            boolean reverted;
            try {
                reverted = revert(pay);
            } catch (RuntimeException e) {
                log.error("撤回已收款项失败，需财务核对 payNo={} channel={} amount={}", pay.getPayNo(), pay.getChannel(),
                        pay.getAmount(), e);
                reverted = false;
            }
            if (!reverted) {
                meterRegistry.counter("clinic.checkout.undo.failed").increment();
                others = true;
            }
            tenderMapper.mark(pay.getPayNo(), reverted ? CounterCheckoutTender.REVERTED
                    : CounterCheckoutTender.REVERT_FAILED, pay.getChannelTradeNo(), pay.getTenderRef());
        }
        if (cash) {
            meterRegistry.counter("clinic.checkout.cash.unconfirmed").increment();
        }
        return new Undo(cash, others);
    }

    /** 撤回一笔非现金款项。@return 是否已撤回（或本来就没有扣款） */
    private boolean revert(PayOrder pay) {
        PayChannel channel = PayChannel.of(pay.getChannel()).orElseThrow();
        return switch (channel) {
            case CASH -> throw new IllegalStateException("现金不能由系统撤回");
            // POS 机是独立终端，系统撤不了，需要收费员在 POS 机上撤销当笔交易，财务核对
            case BANK_CARD -> false;
            case CAMPUS_CARD -> {
                campusCard.getObject().reverse(pay.getPayNo());
                yield true;
            }
            default -> {
                PayChannelClient client = channels.require(pay.getChannel());
                if (client.query(pay.getPayNo()).state() != TradeState.SUCCESS) {
                    client.close(pay.getPayNo());
                    yield true;
                }
                yield client.refund(pay.getPayNo(), "U" + pay.getPayNo(), pay.getAmount(), pay.getAmount())
                        .state() == PayChannelClient.RefundState.SUCCESS;
            }
        };
    }

    // ------------------------------------------------------------------ 登记

    private void record(ChargeOrder order, List<PayOrder> pays, Long cashierId, Long shiftId, LocalDateTime now) {
        for (PayOrder pay : pays) {
            payOrderMapper.insert(pay);
        }
        if (orderMapper.markPaid(order.getId(), order.getPayableAmount().amount(), now, cashierId, shiftId) == 0) {
            // 锁单后收费单不应再变化；万一变化（数据被人工改动）则整体回滚，已收款项原路撤回
            throw new BizException(ChargeErrorCode.ORDER_NOT_PAYABLE, "收费单状态已变化，请刷新后重试");
        }
        List<Map<Integer, FundLedgerApi.Split>> splits = TenderAllocation.allocate(amountsByItemType(order.getId()),
                pays.stream().map(PayOrder::getAmount).toList());
        for (int i = 0; i < pays.size(); i++) {
            PayOrder pay = pays.get(i);
            fundLedgerApi.recordPayment(new FundLedgerApi.PaymentEntry(now.toLocalDate(), order.getCampusId(),
                    order.getDeptId(), shiftId, pay.getPayNo(), pay.getChannel(), order.getPatientType(),
                    splits.get(i)));
        }
        events.publishEvent(new ChargePaidEvent(order.getId(), order.getSourceType(), order.getRegistrationId(),
                order.getPatientId()));
        chargeOutbox.paid(order);
    }

    private PayOrder newPayOrder(ChargeOrder order, Planned p, String idemKey, LocalDateTime now) {
        PayOrder pay = new PayOrder();
        pay.setPayNo(BizNoGenerator.next(PAY_NO_PREFIX, clock));
        pay.setChargeOrderId(order.getId());
        pay.setChannel(p.channel().code());
        pay.setAmount(p.tender().amount());
        pay.setStatus(PayStatus.SUCCESS);
        pay.setIdempotencyKey(idemKey);
        pay.setRefundedAmount(Money.ZERO);
        pay.setExpireTime(now);
        pay.setSuccessTime(now);
        return pay;
    }

    private Map<Integer, FundLedgerApi.Split> amountsByItemType(Long chargeOrderId) {
        Map<Integer, FundLedgerApi.Split> byType = new TreeMap<>();
        for (ChargeOrderItem item : itemMapper.selectList(Wrappers.<ChargeOrderItem>lambdaQuery()
                .eq(ChargeOrderItem::getChargeOrderId, chargeOrderId)
                .eq(ChargeOrderItem::getValid, 1))) {
            byType.merge(item.getItemType(), new FundLedgerApi.Split(item.getSelfAmount(), item.getInsuranceAmount()),
                    FundLedgerApi.Split::add);
        }
        return byType;
    }

    // ------------------------------------------------------------------ 结果

    private CheckoutResultVO replay(ChargeOrder order, String idemKey, CheckoutDTO dto) {
        List<PayOrder> pays = payOrderMapper.selectList(Wrappers.<PayOrder>lambdaQuery()
                .eq(PayOrder::getChargeOrderId, order.getId())
                .and(w -> w.eq(PayOrder::getIdempotencyKey, idemKey).or()
                        .likeRight(PayOrder::getIdempotencyKey, idemKey + "#"))
                .orderByAsc(PayOrder::getId));
        InsuranceSettlement settlement = insurance.listByOrder(order.getId()).stream()
                .filter(s -> s.getStatus() == InsuranceSettlement.SETTLED).findFirst().orElse(null);
        Money change = Money.ZERO;
        for (Tender t : dto.tenders()) {
            if (t.method() == Method.CASH && t.received() != null && t.received().greaterThan(t.amount())) {
                change = t.received().subtract(t.amount());
            }
        }
        return result(orderMapper.selectById(order.getId()), settlement, pays, change);
    }

    private static CheckoutResultVO result(ChargeOrder order, InsuranceSettlement settlement, List<PayOrder> pays,
                                           Money change) {
        return new CheckoutResultVO(order.getChargeNo(), order.getTotalAmount(), order.getInsuranceAmount(),
                order.getPayableAmount(), settlement == null ? null : settlement.getSetlId(), change,
                pays.stream().map(p -> new CheckoutResultVO.TenderVO(p.getPayNo(), p.getChannel(), p.getAmount(),
                        p.getChannelTradeNo(), p.getTenderRef())).toList());
    }

    private static Money changeOf(List<Planned> plan) {
        for (Planned p : plan) {
            Tender t = p.tender();
            if (t.method() == Method.CASH && t.received() != null) {
                return t.received().subtract(t.amount());
            }
        }
        return Money.ZERO;
    }

    private static String mask(String cardNo) {
        return cardNo.length() <= 4 ? cardNo : "*".repeat(cardNo.length() - 4) + cardNo.substring(cardNo.length() - 4);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
