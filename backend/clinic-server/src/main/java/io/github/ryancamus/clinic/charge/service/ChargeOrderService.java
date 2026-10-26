package io.github.ryancamus.clinic.charge.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.github.ryancamus.clinic.charge.api.ChargeApi;
import io.github.ryancamus.clinic.charge.api.ChargePaidEvent;
import io.github.ryancamus.clinic.charge.api.PriceQueryApi;
import io.github.ryancamus.clinic.charge.config.ChargeProperties;
import io.github.ryancamus.clinic.charge.domain.dto.ChargeCreateDTO;
import io.github.ryancamus.clinic.charge.domain.dto.ChargeItemDTO;
import io.github.ryancamus.clinic.charge.domain.entity.ChargeOrder;
import io.github.ryancamus.clinic.charge.domain.entity.ChargeOrderItem;
import io.github.ryancamus.clinic.charge.domain.entity.PayOrder;
import io.github.ryancamus.clinic.charge.domain.query.ChargeOrderQuery;
import io.github.ryancamus.clinic.charge.domain.vo.ChargeItemVO;
import io.github.ryancamus.clinic.charge.domain.vo.ChargeOrderVO;
import io.github.ryancamus.clinic.charge.domain.vo.ChargePreviewVO;
import io.github.ryancamus.clinic.charge.domain.vo.PayOrderVO;
import io.github.ryancamus.clinic.charge.domain.vo.RefundVO;
import io.github.ryancamus.clinic.charge.enums.ChargeErrorCode;
import io.github.ryancamus.clinic.charge.enums.ChargeItemType;
import io.github.ryancamus.clinic.charge.enums.ChargeSource;
import io.github.ryancamus.clinic.charge.enums.ChargeStatus;
import io.github.ryancamus.clinic.charge.fee.FeeCalculator.Breakdown;
import io.github.ryancamus.clinic.charge.fee.FeeCalculator.ChargedLine;
import io.github.ryancamus.clinic.charge.fee.FeeCalculator.FeeLine;
import io.github.ryancamus.clinic.charge.insurance.InsuranceGateway;
import io.github.ryancamus.clinic.charge.mapper.ChargeOrderItemMapper;
import io.github.ryancamus.clinic.charge.mapper.ChargeOrderMapper;
import io.github.ryancamus.clinic.charge.mapper.PayOrderMapper;
import io.github.ryancamus.clinic.common.exception.BizException;
import io.github.ryancamus.clinic.common.money.Money;
import io.github.ryancamus.clinic.common.result.PageResult;
import io.github.ryancamus.clinic.framework.util.BizNoGenerator;
import io.github.ryancamus.clinic.patient.api.PatientQueryApi.PatientSnapshot;
import io.github.ryancamus.clinic.patient.api.PatientQueryApi;
import io.github.ryancamus.clinic.pharmacy.api.DrugQueryApi;
import io.github.ryancamus.clinic.pharmacy.api.StockApi;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * 划价与收费单生命周期。
 * <p>
 * 划价时按明细计算医保统筹与个人自付并作为快照写入：医保平台接入后用其预结算（{@link InsuranceGateway#preSettle}），
 * 未接入时按本院配置的计费策略估算，实际以医保结算终端的结算结果为准。
 * 之后改价、调整报销比例都不影响已开出的单据；收费时由 {@link InsuranceSettlementService} 办理或登记医保结算。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChargeOrderService implements ChargeApi {

    private static final String CHARGE_NO_PREFIX = "C";

    private final ChargeOrderMapper orderMapper;
    private final ChargeOrderItemMapper itemMapper;
    private final PayOrderMapper payOrderMapper;
    private final ChargeOrderGuard orderGuard;
    private final PriceQueryApi priceQueryApi;
    private final PatientQueryApi patientQueryApi;
    private final InsuranceSettlementService insuranceSettlements;
    private final DrugQueryApi drugQueryApi;
    private final RefundService refundService;
    private final ChargeOutbox chargeOutbox;
    private final StockApi stockApi;
    private final ApplicationEventPublisher events;
    private final ChargeProperties properties;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;

    // ------------------------------------------------------------------ 划价

    /** 试算：返回按患者参保类型计算的统筹与自付金额，不落库 */
    public ChargePreviewVO preview(ChargeCreateDTO dto) {
        PatientSnapshot patient = requirePatient(dto.patientId());
        Breakdown b = calculate(patient, dto.items(), LocalDateTime.now(clock));
        return new ChargePreviewVO(b.policy().code(), b.policy().name(),
                b.lines().stream().map(ChargeOrderService::toItemVO).toList(),
                b.totalAmount(), b.insuranceAmount(), b.selfAmount());
    }

    /** 收费处手工划价生成收费单，未缴费的在 counter-order-ttl 后自动作废 */
    public ChargeOrderVO createCounterOrder(ChargeCreateDTO dto) {
        PatientSnapshot patient = requirePatient(dto.patientId());
        LocalDateTime now = LocalDateTime.now(clock);
        Breakdown b = calculate(patient, dto.items(), now);
        ChargeOrder order = transactionTemplate.execute(tx -> persist(patient, patient.campusId(), null, b,
                ChargeSource.PRESCRIPTION, null, ChargeSource.REF_MANUAL, null,
                now.plus(properties.counterOrderTtl())).order());
        return detail(order.getId());
    }

    @Override
    public ChargeOrderView createForRegistration(RegistrationCharge cmd) {
        PatientSnapshot patient = requirePatient(cmd.patientId());
        // 按预约当时生效的价格计费：预约后改价不影响已预约的患者
        PriceQueryApi.PriceSnapshot price = priceQueryApi.findEffective(cmd.itemCode(), cmd.bookedAt())
                .orElseThrow(() -> new BizException(ChargeErrorCode.PRICE_ITEM_NOT_FOUND));
        Breakdown b = preSettle(patient, List.of(toLine(price, 1)));
        ChargeOrder order = transactionTemplate.execute(tx -> persist(patient, patient.campusId(), cmd.deptId(), b,
                cmd.sourceType(), cmd.registrationId(), cmd.sourceType(), List.of(cmd.registrationId()),
                cmd.expireTime()).order());
        return toView(order);
    }

    @Override
    public ChargeOrderView createForPrescription(PrescriptionCharge cmd) {
        PatientSnapshot patient = requirePatient(cmd.patientId());
        LocalDateTime now = LocalDateTime.now(clock);
        Map<Long, DrugQueryApi.DrugSnapshot> drugs = drugQueryApi.findByIds(cmd.lines().stream()
                .map(PrescriptionLine::drugId).filter(Objects::nonNull).distinct().toList());
        List<FeeLine> lines = new ArrayList<>(cmd.lines().size());
        for (PrescriptionLine line : cmd.lines()) {
            if (line.drugId() != null) {
                DrugQueryApi.DrugSnapshot drug = drugs.get(line.drugId());
                BizException.throwIf(drug == null || !drug.enabled(), ChargeErrorCode.PRICE_ITEM_NOT_FOUND,
                        "药品不存在或已停用");
                lines.add(new FeeLine(drug.drugCode(), drug.displayName(),
                        drug.goodsType() == 2 ? ChargeItemType.MATERIAL : ChargeItemType.DRUG,
                        drug.retailPrice(), line.qty(), drug.insurable()));
            } else {
                PriceQueryApi.PriceSnapshot price = priceQueryApi.findEffective(line.itemCode(), now)
                        .orElseThrow(() -> new BizException(ChargeErrorCode.PRICE_ITEM_NOT_FOUND,
                                "收费项目不存在或未生效：" + line.itemCode()));
                lines.add(toLine(price, line.qty()));
            }
        }
        Breakdown b = preSettle(patient, lines);
        return transactionTemplate.execute(tx -> {
            Persisted p = persist(patient, cmd.campusId(), cmd.deptId(), b, ChargeSource.PRESCRIPTION, cmd.registrationId(),
                    ChargeSource.PRESCRIPTION, cmd.lines().stream().map(PrescriptionLine::rxItemId).toList(),
                    now.plus(properties.counterOrderTtl()));
            // 开单即锁库存：患者缴费前药品不会被别人开走，库存不足时整张处方回滚
            List<StockApi.LockLine> locks = new ArrayList<>();
            for (int i = 0; i < cmd.lines().size(); i++) {
                if (cmd.lines().get(i).drugId() != null) {
                    locks.add(new StockApi.LockLine(p.items().get(i).getId(), cmd.lines().get(i).drugId(),
                            cmd.lines().get(i).qty()));
                }
            }
            stockApi.lock(new StockApi.LockRequest(cmd.campusId(), p.order().getChargeNo(), locks));
            return toView(p.order());
        });
    }

    private Breakdown calculate(PatientSnapshot patient, List<ChargeItemDTO> items, LocalDateTime at) {
        Set<String> seen = new HashSet<>();
        List<FeeLine> lines = new ArrayList<>(items.size());
        for (ChargeItemDTO item : items) {
            BizException.throwIf(!seen.add(item.itemCode()), ChargeErrorCode.DUPLICATE_ITEM);
            PriceQueryApi.PriceSnapshot price = priceQueryApi.findEffective(item.itemCode(), at)
                    .orElseThrow(() -> new BizException(ChargeErrorCode.PRICE_ITEM_NOT_FOUND,
                            "收费项目不存在或未生效：" + item.itemCode()));
            lines.add(toLine(price, item.qty()));
        }
        return preSettle(patient, lines);
    }

    /** 划价即预结算：统筹与自付由医保中心计算（未接入时按本院配置的报销规则估算），结果作为快照写入收费单 */
    private Breakdown preSettle(PatientSnapshot patient, List<FeeLine> lines) {
        return insuranceSettlements.estimate(new InsuranceGateway.Insured(patient.id(), patient.insuranceType()), lines);
    }

    private record Persisted(ChargeOrder order, List<ChargeOrderItem> items) {
    }

    /**
     * @param refIds 与明细一一对应的来源 ID；为空时每条明细以自身 ID 作为来源（手工划价没有来源单据）
     */
    private Persisted persist(PatientSnapshot patient, Long campusId, Long deptId, Breakdown b, int sourceType,
                              Long registrationId, int refType, List<Long> refIds, LocalDateTime expireTime) {
        LocalDateTime now = LocalDateTime.now(clock);
        boolean free = b.selfAmount().isZero();
        ChargeOrder order = new ChargeOrder();
        order.setId(IdWorker.getId());
        order.setChargeNo(BizNoGenerator.next(CHARGE_NO_PREFIX, clock));
        order.setCampusId(campusId);
        order.setDeptId(deptId);
        order.setPatientId(patient.id());
        order.setPatientType(patient.patientType());
        order.setRegistrationId(registrationId);
        order.setSourceType(sourceType);
        // 全部由医保记账、个人无需付款的单据直接视为已付清
        order.setStatus(free ? ChargeStatus.PAID.code() : ChargeStatus.UNPAID.code());
        order.setTotalAmount(b.totalAmount());
        order.setInsuranceAmount(b.insuranceAmount());
        order.setDiscountAmount(Money.ZERO);
        order.setPayableAmount(b.selfAmount());
        order.setPaidAmount(Money.ZERO);
        order.setRefundedAmount(Money.ZERO);
        order.setFeePolicyCode(b.policy().code());
        order.setExpireTime(expireTime);
        order.setPaidTime(free ? now : null);
        orderMapper.insert(order);

        List<ChargeOrderItem> items = new ArrayList<>(b.lines().size());
        for (int i = 0; i < b.lines().size(); i++) {
            ChargedLine line = b.lines().get(i);
            ChargeOrderItem item = new ChargeOrderItem();
            item.setId(IdWorker.getId());
            item.setChargeOrderId(order.getId());
            item.setItemType(line.line().itemType());
            item.setRefType(refType);
            item.setRefId(refIds != null ? refIds.get(i) : item.getId());
            item.setItemCode(line.line().itemCode());
            item.setItemName(line.line().itemName());
            item.setUnitPrice(line.line().unitPrice());
            item.setQty(line.line().qty());
            item.setAmount(line.amount());
            item.setInsuranceRatio(line.insuranceRatio());
            item.setInsuranceAmount(line.insuranceAmount());
            item.setSelfAmount(line.selfAmount());
            item.setRefundedQty(0);
            item.setDispensedQty(0);
            item.setValid(1);
            itemMapper.insert(item);
            items.add(item);
        }
        if (free) {
            events.publishEvent(new ChargePaidEvent(order.getId(), sourceType, registrationId, patient.id()));
            chargeOutbox.paid(order);
        }
        return new Persisted(order, items);
    }

    // ------------------------------------------------------------------ 关单

    @Override
    public Optional<ChargeOrderView> findView(Long chargeOrderId) {
        return Optional.ofNullable(orderMapper.selectById(chargeOrderId)).map(ChargeOrderService::toView);
    }

    @Override
    public CloseOutcome closeIfUnpaid(Long chargeOrderId) {
        ChargeOrder order = orderMapper.selectById(chargeOrderId);
        if (order == null) {
            return CloseOutcome.CLOSED;
        }
        ChargeStatus status = ChargeStatus.of(order.getStatus());
        if (status != ChargeStatus.UNPAID) {
            return status == ChargeStatus.CLOSED ? CloseOutcome.CLOSED : CloseOutcome.PAID;
        }
        if (orderGuard.blockingCheckout(chargeOrderId) != null) {
            // 窗口正在收费，或有待财务核对的收款异常
            return CloseOutcome.IN_PROGRESS;
        }
        // 锁住收费单后再判断一次并关单：与窗口开始结算互斥
        return orderGuard.withLock(chargeOrderId, locked -> {
            ChargeStatus current = ChargeStatus.of(locked.getStatus());
            if (current != ChargeStatus.UNPAID) {
                return current == ChargeStatus.CLOSED ? CloseOutcome.CLOSED : CloseOutcome.PAID;
            }
            if (orderGuard.blockingCheckout(chargeOrderId) != null) {
                // 刚有窗口开始收费
                return CloseOutcome.IN_PROGRESS;
            }
            orderMapper.casStatus(chargeOrderId, ChargeStatus.UNPAID.code(), ChargeStatus.CLOSED.code());
            itemMapper.invalidateByOrder(chargeOrderId);
            // 关单释放开单时锁定的药品
            List<StockApi.ReleaseLine> release = itemMapper.selectList(Wrappers.<ChargeOrderItem>lambdaQuery()
                            .eq(ChargeOrderItem::getChargeOrderId, chargeOrderId))
                    .stream().filter(i -> ChargeItemType.isGoods(i.getItemType()))
                    .map(i -> new StockApi.ReleaseLine(i.getId(), 0)).toList();
            if (!release.isEmpty()) {
                stockApi.releaseDownTo(release, order.getChargeNo());
            }
            return CloseOutcome.CLOSED;
        });
    }

    @Override
    public void markRegistrationServed(Long registrationId) {
        itemMapper.markRegistrationServed(registrationId);
    }

    /** 收费处作废未缴费的收费单 */
    public void cancel(Long chargeOrderId) {
        requireOrder(chargeOrderId);
        CloseOutcome outcome = closeIfUnpaid(chargeOrderId);
        if (outcome == CloseOutcome.IN_PROGRESS) {
            // 说明为什么不能作废：正在收费，或收款异常待财务处理
            orderGuard.requireNoBlockingCheckout(chargeOrderId);
        }
        BizException.throwIf(outcome != CloseOutcome.CLOSED, ChargeErrorCode.ORDER_NOT_CLOSABLE);
    }

    /**
     * 作废超过付款期限的收费单，由定时任务调用。
     *
     * @return 本次关闭的数量
     */
    public int expireOverdue(int limit) {
        int closed = 0;
        for (Long id : orderMapper.selectExpiredUnpaidIds(LocalDateTime.now(clock), limit)) {
            try {
                if (closeIfUnpaid(id) == CloseOutcome.CLOSED) {
                    closed++;
                }
            } catch (RuntimeException e) {
                log.warn("超时关单失败 chargeOrderId={}", id, e);
            }
        }
        if (closed > 0) {
            log.info("超时未缴费收费单已关闭 {} 张", closed);
        }
        return closed;
    }

    // ------------------------------------------------------------------ 查询

    public PageResult<ChargeOrderVO> page(ChargeOrderQuery query) {
        LambdaQueryWrapper<ChargeOrder> wrapper = Wrappers.<ChargeOrder>lambdaQuery()
                .eq(query.chargeNo() != null && !query.chargeNo().isBlank(), ChargeOrder::getChargeNo,
                        query.chargeNo() == null ? null : query.chargeNo().trim())
                .eq(query.patientId() != null, ChargeOrder::getPatientId, query.patientId())
                .eq(query.status() != null, ChargeOrder::getStatus, query.status())
                .ge(query.dateFrom() != null, ChargeOrder::getCreateTime,
                        query.dateFrom() == null ? null : query.dateFrom().atStartOfDay())
                .lt(query.dateTo() != null, ChargeOrder::getCreateTime,
                        query.dateTo() == null ? null : query.dateTo().plusDays(1).atStartOfDay())
                .orderByDesc(ChargeOrder::getId);
        Page<ChargeOrder> page = orderMapper.selectPage(Page.of(query.pageOrDefault(), query.sizeOrDefault()), wrapper);
        Map<Long, String> names = patientQueryApi.names(
                page.getRecords().stream().map(ChargeOrder::getPatientId).distinct().toList());
        return new PageResult<>(page.getRecords().stream()
                .map(o -> toVO(o, names.get(o.getPatientId()), null, null)).toList(),
                page.getTotal(), page.getCurrent(), page.getSize());
    }

    public ChargeOrderVO detail(Long chargeOrderId) {
        return detailOf(requireOrder(chargeOrderId));
    }

    private ChargeOrderVO detailOf(ChargeOrder order) {
        List<ChargeItemVO> items = itemMapper.selectList(Wrappers.<ChargeOrderItem>lambdaQuery()
                        .eq(ChargeOrderItem::getChargeOrderId, order.getId())
                        .orderByAsc(ChargeOrderItem::getId))
                .stream().map(ChargeOrderService::toItemVO).toList();
        List<PayOrderVO> pays = payOrderMapper.selectList(Wrappers.<PayOrder>lambdaQuery()
                        .eq(PayOrder::getChargeOrderId, order.getId())
                        .orderByAsc(PayOrder::getId))
                .stream().map(p -> new PayOrderVO(p.getPayNo(), p.getChannel(), p.getAmount(), p.getStatus(),
                        p.getChannelTradeNo(), p.getSuccessTime(), p.getCreateTime())).toList();
        String name = patientQueryApi.names(List.of(order.getPatientId())).get(order.getPatientId());
        return toVO(order, name, items, pays, refundService.listByOrder(order.getId()));
    }

    // ------------------------------------------------------------------ 转换

    private PatientSnapshot requirePatient(Long patientId) {
        return patientQueryApi.findById(patientId)
                .orElseThrow(() -> new BizException(ChargeErrorCode.PATIENT_NOT_FOUND));
    }

    private ChargeOrder requireOrder(Long chargeOrderId) {
        ChargeOrder order = orderMapper.selectById(chargeOrderId);
        if (order == null) {
            throw new BizException(ChargeErrorCode.ORDER_NOT_FOUND);
        }
        return order;
    }

    private static FeeLine toLine(PriceQueryApi.PriceSnapshot price, int qty) {
        return new FeeLine(price.itemCode(), price.itemName(), price.itemType(), price.unitPrice(), qty,
                price.insurable());
    }

    private static ChargeOrderView toView(ChargeOrder o) {
        return new ChargeOrderView(o.getId(), o.getChargeNo(), o.getStatus(), o.getTotalAmount(),
                o.getInsuranceAmount(), o.getPayableAmount(), o.getFeePolicyCode(), o.getExpireTime());
    }

    private static ChargeItemVO toItemVO(ChargedLine l) {
        return new ChargeItemVO(null, l.line().itemCode(), l.line().itemName(), l.line().itemType(),
                l.line().unitPrice(), l.line().qty(), l.amount(), l.insuranceRatio(), l.insuranceAmount(),
                l.selfAmount(), 0, 0);
    }

    private static ChargeItemVO toItemVO(ChargeOrderItem i) {
        return new ChargeItemVO(i.getId(), i.getItemCode(), i.getItemName(), i.getItemType(), i.getUnitPrice(),
                i.getQty(), i.getAmount(), i.getInsuranceRatio(), i.getInsuranceAmount(), i.getSelfAmount(),
                i.getRefundedQty(), i.getDispensedQty());
    }

    private static ChargeOrderVO toVO(ChargeOrder o, String patientName, List<ChargeItemVO> items,
                                      List<PayOrderVO> pays) {
        return toVO(o, patientName, items, pays, null);
    }

    private static ChargeOrderVO toVO(ChargeOrder o, String patientName, List<ChargeItemVO> items,
                                      List<PayOrderVO> pays, List<RefundVO> refunds) {
        return new ChargeOrderVO(o.getId(), o.getChargeNo(), o.getPatientId(), patientName, o.getPatientType(),
                o.getSourceType(), o.getStatus(), o.getTotalAmount(), o.getInsuranceAmount(), o.getPayableAmount(),
                o.getPaidAmount(), o.getRefundedAmount(), o.getFeePolicyCode(), o.getExpireTime(), o.getPaidTime(),
                o.getCreateTime(), items, pays, refunds);
    }
}
