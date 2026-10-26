package io.github.ryancamus.clinic.charge.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.github.ryancamus.clinic.charge.api.ChargeRefundedEvent;
import io.github.ryancamus.clinic.charge.campuscard.CampusCardGateway;
import io.github.ryancamus.clinic.charge.config.ChargeProperties;
import io.github.ryancamus.clinic.charge.domain.dto.CheckoutDTO;
import io.github.ryancamus.clinic.charge.domain.dto.RefundApplyDTO;
import io.github.ryancamus.clinic.charge.domain.entity.ChargeOrder;
import io.github.ryancamus.clinic.charge.domain.entity.ChargeOrderItem;
import io.github.ryancamus.clinic.charge.domain.entity.PayOrder;
import io.github.ryancamus.clinic.charge.domain.entity.RefundItem;
import io.github.ryancamus.clinic.charge.domain.entity.RefundOrder;
import io.github.ryancamus.clinic.charge.domain.entity.RefundTender;
import io.github.ryancamus.clinic.charge.domain.vo.RefundVO;
import io.github.ryancamus.clinic.charge.enums.ChargeErrorCode;
import io.github.ryancamus.clinic.charge.enums.ChargeItemType;
import io.github.ryancamus.clinic.charge.enums.ChargeStatus;
import io.github.ryancamus.clinic.charge.enums.PayChannel;
import io.github.ryancamus.clinic.charge.enums.PayStatus;
import io.github.ryancamus.clinic.charge.enums.RefundStatus;
import io.github.ryancamus.clinic.charge.fee.FeeCalculator.ChargedLine;
import io.github.ryancamus.clinic.charge.fee.FeeCalculator.FeeLine;
import io.github.ryancamus.clinic.charge.fee.FeeCalculator.RefundSplit;
import io.github.ryancamus.clinic.charge.fee.FeeCalculator;
import io.github.ryancamus.clinic.charge.mapper.ChargeOrderItemMapper;
import io.github.ryancamus.clinic.charge.mapper.ChargeOrderMapper;
import io.github.ryancamus.clinic.charge.mapper.PayOrderMapper;
import io.github.ryancamus.clinic.charge.mapper.RefundItemMapper;
import io.github.ryancamus.clinic.charge.mapper.RefundOrderMapper;
import io.github.ryancamus.clinic.charge.mapper.RefundTenderMapper;
import io.github.ryancamus.clinic.charge.pay.PayChannelClient.RefundResult;
import io.github.ryancamus.clinic.charge.pay.PayChannelClient.RefundState;
import io.github.ryancamus.clinic.charge.pay.PayChannelClient;
import io.github.ryancamus.clinic.charge.pay.PayChannelRegistry;
import io.github.ryancamus.clinic.common.exception.BizException;
import io.github.ryancamus.clinic.common.exception.CommonErrorCode;
import io.github.ryancamus.clinic.common.money.Money;
import io.github.ryancamus.clinic.common.result.PageResult;
import io.github.ryancamus.clinic.finance.api.FinanceShiftApi;
import io.github.ryancamus.clinic.finance.api.FundLedgerApi;
import io.github.ryancamus.clinic.framework.util.BizNoGenerator;
import io.github.ryancamus.clinic.pharmacy.api.StockApi;
import io.github.ryancamus.clinic.system.api.OrgQueryApi;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 退费：按明细、按数量，原路退回。
 * <ol>
 *   <li>申请：逐条明细预占退费数量（条件更新，已发药的不能退），按“累计已退件数”计算统筹与自付的退款额；</li>
 *   <li>审批：自付部分不超过阈值自动通过，否则财务审批；驳回归还预占数量；</li>
 *   <li>执行：调渠道原路退款（现金、银行卡由收费员当面退还后确认），全部成功后累加支付单、收费单已退金额（均有不超额条件），
 *       记负数资金流水（红冲），释放不再需要的药品锁定；失败的由定时任务重试。</li>
 * </ol>
 * 同一收费单同时只允许一笔未完结退费（数据库唯一键），每笔退费都基于确定的前序状态计算金额，
 * 并发申请只有一笔能进入，其余提示“正在处理”。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RefundService {

    private static final String REFUND_NO_PREFIX = "F";
    private static final Pattern CLIENT_KEY = Pattern.compile("[A-Za-z0-9-]{8,40}");
    private static final int MAX_RETRY = 5;
    private static final Pattern POS_REF = Pattern.compile("[A-Za-z0-9]{6,32}");

    private final RefundOrderMapper refundMapper;
    private final RefundItemMapper refundItemMapper;
    private final ChargeOrderMapper orderMapper;
    private final ChargeOrderItemMapper itemMapper;
    private final PayOrderMapper payOrderMapper;
    private final RefundTenderMapper refundTenderMapper;
    private final ObjectProvider<CampusCardGateway> campusCard;
    private final InsuranceSettlementService insuranceSettlements;
    private final PayChannelRegistry channels;
    private final FundLedgerApi fundLedgerApi;
    private final StockApi stockApi;
    private final ApplicationEventPublisher events;
    private final ChargeOutbox chargeOutbox;
    private final FinanceShiftApi shiftApi;
    private final ChargeProperties properties;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;
    private final OrgQueryApi orgQueryApi;

    // ------------------------------------------------------------------ 申请

    public RefundVO apply(Long chargeOrderId, RefundApplyDTO dto, String clientKey, Long operatorId) {
        if (clientKey == null || !CLIENT_KEY.matcher(clientKey).matches()) {
            throw new BizException(CommonErrorCode.BAD_REQUEST, "请求头 Idempotency-Key 缺失或格式不正确");
        }
        String idemKey = "R" + operatorId + ":" + clientKey;
        RefundOrder existing = refundMapper.selectByIdempotencyKey(idemKey);
        if (existing != null) {
            BizException.throwIf(!existing.getChargeOrderId().equals(chargeOrderId),
                    ChargeErrorCode.IDEMPOTENCY_KEY_REUSED);
            return toVO(existing, null);
        }
        ChargeOrder order = orderMapper.selectById(chargeOrderId);
        BizException.throwIf(order == null, ChargeErrorCode.ORDER_NOT_FOUND);
        ChargeStatus status = ChargeStatus.of(order.getStatus());
        BizException.throwIf(status != ChargeStatus.PAID && status != ChargeStatus.PARTIAL_REFUNDED,
                ChargeErrorCode.ORDER_NOT_REFUNDABLE);
        // 组合付款的收费单按收款逆序原路退回（后收的先退）
        List<PayOrder> pays = payOrderMapper.selectList(Wrappers.<PayOrder>lambdaQuery()
                .eq(PayOrder::getChargeOrderId, chargeOrderId)
                .eq(PayOrder::getStatus, PayStatus.SUCCESS)
                .orderByDesc(PayOrder::getId));
        BizException.throwIf(pays.isEmpty(), ChargeErrorCode.NOTHING_TO_REFUND);
        // 现金、校园卡、银行卡在窗口退还，计入办理人当班
        Long shiftId = pays.stream().anyMatch(p -> PayChannel.isCounter(p.getChannel()))
                ? shiftApi.ensureOpenShift(operatorId, order.getCampusId()) : null;

        // 同一明细的多行合并，按明细 id 升序预占，与发药等操作的加锁顺序一致
        Map<Long, Integer> qtyByItem = new TreeMap<>();
        dto.items().forEach(l -> qtyByItem.merge(l.chargeItemId(), l.qty(), Integer::sum));

        RefundOrder refund;
        try {
            refund = transactionTemplate.execute(tx -> {
                List<RefundItem> items = new ArrayList<>();
                Money self = Money.ZERO;
                Money insurance = Money.ZERO;
                for (Map.Entry<Long, Integer> e : qtyByItem.entrySet()) {
                    ChargeOrderItem item = itemMapper.selectById(e.getKey());
                    BizException.throwIf(item == null || !item.getChargeOrderId().equals(chargeOrderId),
                            ChargeErrorCode.REFUND_QTY_EXCEEDED, "明细不属于该收费单");
                    if (itemMapper.reserveRefund(item.getId(), e.getValue()) == 0) {
                        // 挂号费在医生接诊时记为“已提供服务”（同药品已发出），已就诊的不退
                        BizException.throwIf(item.getItemType() == ChargeItemType.REGISTRATION
                                && item.getDispensedQty() > 0, ChargeErrorCode.REGISTRATION_FEE_SERVED);
                        throw new BizException(ChargeErrorCode.REFUND_QTY_EXCEEDED);
                    }
                    // 预占成功后本事务持有该行锁，读到的已退数量含本次；减去本次即为此前累计
                    int prior = itemMapper.selectById(item.getId()).getRefundedQty() - e.getValue();
                    RefundSplit split = FeeCalculator.refund(toChargedLine(item), prior, e.getValue());
                    RefundItem ri = new RefundItem();
                    ri.setChargeItemId(item.getId());
                    ri.setQty(e.getValue());
                    ri.setAmount(split.amount());
                    ri.setInsuranceAmount(split.insuranceAmount());
                    ri.setSelfAmount(split.selfAmount());
                    items.add(ri);
                    self = self.add(split.selfAmount());
                    insurance = insurance.add(split.insuranceAmount());
                }
                BizException.throwIf(self.isZero(), ChargeErrorCode.NOTHING_TO_REFUND);

                RefundOrder r = new RefundOrder();
                r.setRefundNo(BizNoGenerator.next(REFUND_NO_PREFIX, clock));
                r.setChargeOrderId(chargeOrderId);
                r.setPayOrderId(pays.getFirst().getId());
                r.setAmount(self);
                r.setInsuranceAmount(insurance);
                r.setReason(dto.reason());
                r.setStatus(self.greaterThan(Money.of(properties.refundAutoApproveLimit()))
                        ? RefundStatus.PENDING_APPROVAL : RefundStatus.REFUNDING);
                r.setIdempotencyKey(idemKey);
                r.setShiftId(shiftId);
                r.setRetryCount(0);
                refundMapper.insert(r);
                for (RefundItem ri : items) {
                    ri.setRefundOrderId(r.getId());
                    refundItemMapper.insert(ri);
                }
                return r;
            });
        } catch (DuplicateKeyException e) {
            RefundOrder byKey = refundMapper.selectByIdempotencyKey(idemKey);
            if (byKey != null) {
                return toVO(byKey, null);
            }
            throw new BizException(ChargeErrorCode.REFUND_IN_PROGRESS);
        }
        if (refund.getStatus() == RefundStatus.REFUNDING) {
            execute(refund.getId());
        }
        return toVO(refundMapper.selectById(refund.getId()), null);
    }

    // ------------------------------------------------------------------ 审批

    public RefundVO approve(Long refundId, Long approverId) {
        return approve(refundId, approverId, null);
    }

    /**
     * 审批通过并执行退款。申请人本人不能审批（不相容岗位分离），记录审批人、时间和意见。
     */
    public RefundVO approve(Long refundId, Long approverId, String note) {
        requireOtherApprover(refundId, approverId);
        BizException.throwIf(refundMapper.decide(refundId, RefundStatus.REFUNDING, approverId,
                LocalDateTime.now(clock), blankToNull(note)) == 0, ChargeErrorCode.REFUND_STATE_CHANGED);
        execute(refundId);
        return toVO(refundMapper.selectById(refundId), null);
    }

    /**
     * 驳回：必须写明理由，归还预占的退费数量。申请人本人不能驳回。
     */
    public void reject(Long refundId, Long approverId, String reason) {
        String why = blankToNull(reason);
        BizException.throwIf(why == null, ChargeErrorCode.REJECT_REASON_REQUIRED);
        requireOtherApprover(refundId, approverId);
        transactionTemplate.executeWithoutResult(tx -> {
            BizException.throwIf(refundMapper.decide(refundId, RefundStatus.REJECTED, approverId,
                    LocalDateTime.now(clock), why) == 0, ChargeErrorCode.REFUND_STATE_CHANGED);
            for (RefundItem ri : itemsOf(refundId)) {
                if (itemMapper.cancelRefund(ri.getChargeItemId(), ri.getQty()) == 0) {
                    throw new IllegalStateException("退费预占数量不一致 chargeItemId=" + ri.getChargeItemId());
                }
            }
        });
    }

    // ------------------------------------------------------------------ 执行

    /**
     * 执行退费，可重复调用（定时任务重试）：
     * <ol>
     *   <li>有医保结算的，先撤销原结算、按剩余明细重新结算；失败则保持“退款中”，稍后重试；</li>
     *   <li>逐个收款方式原路退回：校园卡与付款码渠道调平台接口（需已接入），以退费单号幂等，已成功的不再重复；
     *       现金、银行卡转为“待收费员确认”，收费员当面退还（银行卡在 POS 机上退货）后确认（{@link #confirmTender}）；</li>
     *   <li>全部退回后记账、释放药品锁定、发出“已退费”事件。</li>
     * </ol>
     */
    public void execute(Long refundId) {
        RefundOrder refund = refundMapper.selectById(refundId);
        if (refund == null || refund.getStatus() != RefundStatus.REFUNDING) {
            return;
        }
        try {
            insuranceSettlements.resettleAfterRefund(refund.getChargeOrderId(), refund.getRefundNo());
        } catch (RuntimeException e) {
            log.warn("退费前撤销重结医保失败，稍后重试 refundNo={}", refund.getRefundNo(), e);
            return;
        }
        List<RefundTender> tenders = tendersOf(refund);
        int succeeded = 0;
        boolean failed = false;
        for (RefundTender t : tenders) {
            if (t.getStatus() == RefundTender.SUCCESS) {
                succeeded++;
                continue;
            }
            if (needsCashierConfirm(t.getChannel())) {
                // 现金、银行卡：系统退不了钱，转为“待收费员确认”，收费员当面退还后在系统里确认
                if (t.getStatus() == RefundTender.PENDING || t.getStatus() == RefundTender.FAILED) {
                    refundTenderMapper.markAwaitingConfirm(t.getId());
                }
                continue;
            }
            RefundResult result = refundTender(refund, t);
            if (result == null || result.state() == RefundState.PROCESSING) {
                continue;
            }
            if (result.state() == RefundState.SUCCESS) {
                refundTenderMapper.markResult(t.getId(), RefundTender.SUCCESS, result.channelRefundNo());
                t.setStatus(RefundTender.SUCCESS);
                t.setChannelRefundNo(result.channelRefundNo());
                succeeded++;
            } else {
                log.warn("原路退款失败 refundNo={} channel={} reason={}", refund.getRefundNo(), t.getChannel(),
                        result.message());
                refundTenderMapper.markResult(t.getId(), RefundTender.FAILED, null);
                failed = true;
            }
        }
        if (succeeded == tenders.size()) {
            complete(refund, tenders);
        } else if (failed && succeeded == 0) {
            refundMapper.markFailed(refundId);
        }
        // 部分方式已退、部分失败或处理中：保持“退款中”，由定时任务只重试未成功的方式
    }

    private static boolean needsCashierConfirm(int channel) {
        return channel == PayChannel.CASH.code() || channel == PayChannel.BANK_CARD.code();
    }

    /**
     * 收费员确认已把现金当面退还患者，或已在 POS 机上做完退货交易（录入退货参考号）。
     * 记录确认人、时间与所在班次：现金从确认人当班的钱箱付出，交班时计入该班次。全部方式退完后退费完成。
     */
    public RefundVO confirmTender(Long refundId, Long tenderId, String reference, String note, Long cashierId) {
        RefundOrder refund = refundMapper.selectById(refundId);
        BizException.throwIf(refund == null, ChargeErrorCode.REFUND_NOT_FOUND);
        RefundTender t = refundTenderMapper.selectById(tenderId);
        BizException.throwIf(t == null || !t.getRefundOrderId().equals(refundId), ChargeErrorCode.REFUND_NOT_FOUND);
        BizException.throwIf(refund.getStatus() != RefundStatus.REFUNDING || t.getStatus() != RefundTender.AWAITING_CONFIRM,
                ChargeErrorCode.REFUND_TENDER_NOT_AWAITING);
        String ref = reference == null || reference.isBlank() ? null : reference.trim();
        BizException.throwIf(t.getChannel() == PayChannel.BANK_CARD.code()
                && (ref == null || !POS_REF.matcher(ref).matches()), ChargeErrorCode.REFUND_REFERENCE_REQUIRED);
        ChargeOrder order = orderMapper.selectById(refund.getChargeOrderId());
        Long shiftId = shiftApi.ensureOpenShift(cashierId, order.getCampusId());
        BizException.throwIf(refundTenderMapper.confirm(tenderId, ref, cashierId, LocalDateTime.now(clock), shiftId,
                note == null || note.isBlank() ? null : note.trim()) == 0, ChargeErrorCode.REFUND_TENDER_NOT_AWAITING);
        log.info("收费员确认已退还 refundNo={} channel={} amount={} cashier={}", refund.getRefundNo(), t.getChannel(),
                t.getAmount(), cashierId);
        execute(refundId);
        return toVO(refundMapper.selectById(refundId), order.getChargeNo());
    }

    /**
     * 医保平台未接入时的退费：收费员在医保结算终端撤销原结算、按剩余项目重新结算后登记，退费继续执行。
     */
    public RefundVO registerInsurance(Long refundId, CheckoutDTO.InsuranceRegistration registration, Long operatorId) {
        RefundOrder refund = refundMapper.selectById(refundId);
        BizException.throwIf(refund == null, ChargeErrorCode.REFUND_NOT_FOUND);
        BizException.throwIf(refund.getStatus() != RefundStatus.REFUNDING, ChargeErrorCode.REFUND_STATE_CHANGED);
        insuranceSettlements.registerRefundResettlement(refund.getChargeOrderId(), refund.getRefundNo(), registration,
                operatorId);
        execute(refundId);
        return toVO(refundMapper.selectById(refundId), null);
    }

    /** @return 退款结果；结果未知（网络异常、渠道未启用）时返回 null，保持待退 */
    private RefundResult refundTender(RefundOrder refund, RefundTender t) {
        PayOrder pay = payOrderMapper.selectById(t.getPayOrderId());
        PayChannel channel = PayChannel.of(t.getChannel()).orElseThrow();
        try {
            return switch (channel) {
                case CASH, BANK_CARD -> null; // 由收费员确认，见 confirmTender
                case CAMPUS_CARD -> {
                    CampusCardGateway card = campusCard.getIfAvailable();
                    if (card == null) {
                        log.warn("校园卡未开通，退费无法执行 refundNo={}", refund.getRefundNo());
                        yield null;
                    }
                    yield card.refund(pay.getPayNo(), refund.getRefundNo(), t.getAmount());
                }
                default -> {
                    PayChannelClient client = channels.find(pay.getChannel()).orElse(null);
                    if (client == null) {
                        log.warn("支付渠道未启用，退费无法执行 refundNo={}", refund.getRefundNo());
                        yield null;
                    }
                    yield client.refund(pay.getPayNo(), refund.getRefundNo(), t.getAmount(), pay.getAmount());
                }
            };
        } catch (RuntimeException e) {
            // 结果未知，保持待退，由定时任务以同一退费单号重试
            log.warn("原路退款请求异常 refundNo={} channel={}", refund.getRefundNo(), t.getChannel(), e);
            return null;
        }
    }

    /**
     * 本笔退费在各收款方式上的拆分，首次执行时生成。放在执行而不是申请时生成：同一收费单同时只有一笔未完结退费，
     * 执行时前一笔退费已经提交，读到的各支付单已退金额是准确的。
     */
    private List<RefundTender> tendersOf(RefundOrder refund) {
        List<RefundTender> existing = refundTenderMapper.selectByRefund(refund.getId());
        if (!existing.isEmpty()) {
            return existing;
        }
        List<PayOrder> pays = payOrderMapper.selectList(Wrappers.<PayOrder>lambdaQuery()
                .eq(PayOrder::getChargeOrderId, refund.getChargeOrderId())
                .eq(PayOrder::getStatus, PayStatus.SUCCESS)
                .orderByDesc(PayOrder::getId));
        List<RefundTender> tenders = allocate(pays, refund.getAmount());
        try {
            transactionTemplate.executeWithoutResult(tx -> tenders.forEach(t -> {
                t.setRefundOrderId(refund.getId());
                refundTenderMapper.insert(t);
            }));
        } catch (DuplicateKeyException e) {
            // 并发执行（如审批与定时重试同时触发）：以先写入的为准
            return refundTenderMapper.selectByRefund(refund.getId());
        }
        return tenders;
    }

    /** 按收款逆序，把要退的自付金额分到各笔支付上（每笔不超过其尚未退回的金额） */
    private static List<RefundTender> allocate(List<PayOrder> paysNewestFirst, Money self) {
        List<RefundTender> tenders = new ArrayList<>();
        Money left = self;
        for (PayOrder p : paysNewestFirst) {
            if (left.isZero()) {
                break;
            }
            Money refundable = p.getAmount().subtract(p.getRefundedAmount());
            if (refundable.isZero()) {
                continue;
            }
            Money take = left.greaterThan(refundable) ? refundable : left;
            RefundTender t = new RefundTender();
            t.setPayOrderId(p.getId());
            t.setChannel(p.getChannel());
            t.setAmount(take);
            t.setStatus(RefundTender.PENDING);
            tenders.add(t);
            left = left.subtract(take);
        }
        if (!left.isZero()) {
            throw new IllegalStateException("退费金额超过可退余额 remaining=" + left);
        }
        return tenders;
    }

    private void complete(RefundOrder refund, List<RefundTender> tenders) {
        LocalDateTime now = LocalDateTime.now(clock);
        // 现金、银行卡记在确认退还的收费员班次；校园卡记在办理人现在的班次（需审批的退费在通过时，办理人可能已经交班）
        Long shiftId = refund.getShiftId() == null ? null : shiftApi.currentShiftOf(refund.getShiftId());
        transactionTemplate.executeWithoutResult(tx -> {
            if (refundMapper.markSuccess(refund.getId(), tenders.getFirst().getChannelRefundNo(), now) == 0) {
                return;
            }
            boolean ok = orderMapper.addRefunded(refund.getChargeOrderId(), refund.getAmount().amount()) == 1;
            for (RefundTender t : tenders) {
                ok &= payOrderMapper.addRefunded(t.getPayOrderId(), t.getAmount().amount()) == 1;
            }
            if (!ok) {
                // 不可能发生的超额：整笔回滚，退费单回到“退款中”，告警后人工核查
                throw new IllegalStateException("退款将超过实收金额 refundNo=" + refund.getRefundNo());
            }
            ChargeOrder order = orderMapper.selectById(refund.getChargeOrderId());
            List<RefundItem> refundItems = itemsOf(refund.getId());
            Map<Long, ChargeOrderItem> chargeItems = new TreeMap<>();
            itemMapper.selectByIds(refundItems.stream().map(RefundItem::getChargeItemId).toList())
                    .forEach(i -> chargeItems.put(i.getId(), i));

            Map<Integer, FundLedgerApi.Split> byType = new TreeMap<>();
            for (RefundItem ri : refundItems) {
                byType.merge(chargeItems.get(ri.getChargeItemId()).getItemType(),
                        new FundLedgerApi.Split(ri.getSelfAmount(), ri.getInsuranceAmount()), FundLedgerApi.Split::add);
            }
            // 退费记在退费当天，窗口退还的记在办理退费的班次；原收款流水与已交班数据不受影响。
            // 多种方式退回时逐笔记账，流水号为退费单号加序号
            List<Map<Integer, FundLedgerApi.Split>> splits = TenderAllocation.allocate(byType,
                    tenders.stream().map(RefundTender::getAmount).toList());
            for (int i = 0; i < tenders.size(); i++) {
                RefundTender t = tenders.get(i);
                fundLedgerApi.recordRefund(new FundLedgerApi.PaymentEntry(now.toLocalDate(), order.getCampusId(),
                        order.getDeptId(), !PayChannel.isCounter(t.getChannel()) ? null
                                : t.getConfirmShiftId() != null ? t.getConfirmShiftId() : shiftId,
                        i == 0 ? refund.getRefundNo() : refund.getRefundNo() + "-" + (i + 1), t.getChannel(),
                        order.getPatientType(), splits.get(i)));
            }

            // 退掉的药不再需要锁定：每条药品明细只保留“尚待发药”的锁定量
            List<StockApi.ReleaseLine> release = chargeItems.values().stream()
                    .filter(i -> ChargeItemType.isGoods(i.getItemType()))
                    .map(i -> new StockApi.ReleaseLine(i.getId(),
                            i.getQty() - refundItemMapper.sumSucceededQty(i.getId()) - i.getDispensedQty()))
                    .toList();
            if (!release.isEmpty()) {
                stockApi.releaseDownTo(release, order.getChargeNo());
            }
            events.publishEvent(new ChargeRefundedEvent(order.getId(), order.getSourceType(),
                    order.getRegistrationId(), order.getPatientId(),
                    order.getStatus() == ChargeStatus.REFUNDED.code()));
            // 电子票据：冲红原票，按剩余金额重开
            Money refundedTotal = Money.ZERO;
            Money refundedInsurance = Money.ZERO;
            for (RefundOrder r : refundMapper.selectList(Wrappers.<RefundOrder>lambdaQuery()
                    .eq(RefundOrder::getChargeOrderId, order.getId())
                    .eq(RefundOrder::getStatus, RefundStatus.SUCCESS))) {
                refundedInsurance = refundedInsurance.add(r.getInsuranceAmount());
                refundedTotal = refundedTotal.add(r.getInsuranceAmount()).add(r.getAmount());
            }
            chargeOutbox.refunded(order, refund.getRefundNo(), order.getTotalAmount().subtract(refundedTotal),
                    order.getInsuranceAmount().subtract(refundedInsurance),
                    order.getPaidAmount().subtract(order.getRefundedAmount()));
        });
    }

    /** 定时重试：退款中迟迟无结果的、失败未超过上限的 */
    public int retry(int limit) {
        LocalDateTime before = LocalDateTime.now(clock).minus(Duration.ofMinutes(1));
        int done = 0;
        for (RefundOrder r : refundMapper.selectRetryable(before, MAX_RETRY, limit)) {
            if (r.getStatus() == RefundStatus.FAILED) {
                refundMapper.casStatus(r.getId(), RefundStatus.FAILED, RefundStatus.REFUNDING, null);
            }
            execute(r.getId());
            if (refundMapper.selectById(r.getId()).getStatus() == RefundStatus.SUCCESS) {
                done++;
            }
        }
        return done;
    }

    // ------------------------------------------------------------------ 查询

    public PageResult<RefundVO> page(Integer status, long page, long size) {
        Page<RefundOrder> p = refundMapper.selectPage(Page.of(page, size), Wrappers.<RefundOrder>lambdaQuery()
                .eq(status != null, RefundOrder::getStatus, status)
                .orderByDesc(RefundOrder::getId));
        Map<Long, String> chargeNos = new TreeMap<>();
        if (!p.getRecords().isEmpty()) {
            orderMapper.selectByIds(p.getRecords().stream().map(RefundOrder::getChargeOrderId).distinct().toList())
                    .forEach(o -> chargeNos.put(o.getId(), o.getChargeNo()));
        }
        Map<Long, String> names = names(p.getRecords());
        return new PageResult<>(p.getRecords().stream()
                .map(r -> toVO(r, chargeNos.get(r.getChargeOrderId()), names)).toList(),
                p.getTotal(), p.getCurrent(), p.getSize());
    }

    public List<RefundVO> listByOrder(Long chargeOrderId) {
        return refundMapper.selectList(Wrappers.<RefundOrder>lambdaQuery()
                        .eq(RefundOrder::getChargeOrderId, chargeOrderId).orderByAsc(RefundOrder::getId))
                .stream().map(r -> toVO(r, null)).toList();
    }

    // ------------------------------------------------------------------ 内部方法

    private List<RefundItem> itemsOf(Long refundId) {
        return refundItemMapper.selectList(Wrappers.<RefundItem>lambdaQuery()
                .eq(RefundItem::getRefundOrderId, refundId)
                .orderByAsc(RefundItem::getChargeItemId));
    }

    private static ChargedLine toChargedLine(ChargeOrderItem i) {
        FeeLine line = new FeeLine(i.getItemCode(), i.getItemName(), i.getItemType(), i.getUnitPrice(), i.getQty(),
                i.getInsuranceRatio().signum() > 0);
        return new ChargedLine(line, i.getAmount(), i.getInsuranceRatio(), i.getInsuranceAmount(), i.getSelfAmount());
    }

    private Map<Long, String> names(List<RefundOrder> refunds) {
        List<Long> ids = refunds.stream()
                .flatMap(r -> Stream.of(r.getCreateBy(), r.getApproverId()))
                .filter(Objects::nonNull).distinct().toList();
        return ids.isEmpty() ? Map.of() : orgQueryApi.userNicknames(ids);
    }

    private RefundVO toVO(RefundOrder r, String chargeNo) {
        return toVO(r, chargeNo, names(List.of(r)));
    }

    private RefundVO toVO(RefundOrder r, String chargeNo, Map<Long, String> names) {
        List<RefundVO.Item> items = itemsOf(r.getId()).stream()
                .sorted(Comparator.comparing(RefundItem::getChargeItemId))
                .map(i -> new RefundVO.Item(i.getChargeItemId(), i.getQty(), i.getAmount(), i.getInsuranceAmount(),
                        i.getSelfAmount()))
                .toList();
        List<RefundVO.Tender> tenders = refundTenderMapper.selectByRefund(r.getId()).stream()
                .map(t -> new RefundVO.Tender(t.getId(), t.getChannel(), t.getAmount(), t.getStatus(),
                        t.getChannelRefundNo(), t.getConfirmBy(), t.getConfirmTime(), t.getConfirmNote()))
                .toList();
        boolean awaitingInsurance = r.getStatus() == RefundStatus.REFUNDING && !r.getInsuranceAmount().isZero()
                && !insuranceSettlements.connected()
                && insuranceSettlements.listByOrder(r.getChargeOrderId()).stream()
                .noneMatch(x -> x.getBizNo().equals(r.getRefundNo()))
                && !insuranceSettlements.outstanding(r.getChargeOrderId()).isEmpty();
        return new RefundVO(r.getId(), r.getRefundNo(), r.getChargeOrderId(), chargeNo, r.getAmount(),
                r.getInsuranceAmount(), r.getStatus(), r.getReason(), r.getCreateTime(), r.getSuccessTime(), items,
                tenders, awaitingInsurance, r.getCreateBy(), names.get(r.getCreateBy()), r.getApproverId(),
                r.getApproverId() == null ? null : names.get(r.getApproverId()), r.getApproveTime(), r.getApproveNote());
    }

    private void requireOtherApprover(Long refundId, Long approverId) {
        RefundOrder r = refundMapper.selectById(refundId);
        BizException.throwIf(r == null, ChargeErrorCode.REFUND_NOT_FOUND);
        BizException.throwIf(approverId.equals(r.getCreateBy()), ChargeErrorCode.SAME_PERSON);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
