package io.github.ryancamus.clinic.pharmacy.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import io.github.ryancamus.clinic.common.exception.BizException;
import io.github.ryancamus.clinic.patient.api.PatientQueryApi;
import io.github.ryancamus.clinic.pharmacy.api.DispenseOrderPort.DispenseItem;
import io.github.ryancamus.clinic.pharmacy.api.DispenseOrderPort.DispenseOrder;
import io.github.ryancamus.clinic.pharmacy.api.DispenseOrderPort;
import io.github.ryancamus.clinic.pharmacy.domain.dto.DispenseDTO;
import io.github.ryancamus.clinic.pharmacy.domain.dto.ReturnDTO;
import io.github.ryancamus.clinic.pharmacy.domain.entity.Drug;
import io.github.ryancamus.clinic.pharmacy.domain.entity.DrugBatch;
import io.github.ryancamus.clinic.pharmacy.domain.entity.DrugTraceCode;
import io.github.ryancamus.clinic.pharmacy.domain.entity.StockLock;
import io.github.ryancamus.clinic.pharmacy.domain.vo.DispenseOrderVO;
import io.github.ryancamus.clinic.pharmacy.enums.PharmacyErrorCode;
import io.github.ryancamus.clinic.pharmacy.mapper.DrugBatchMapper;
import io.github.ryancamus.clinic.pharmacy.mapper.DrugTraceCodeMapper;
import io.github.ryancamus.clinic.pharmacy.mapper.StockLockMapper;
import io.github.ryancamus.clinic.pharmacy.service.StockService.Allocation;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 发药与退药。
 * <p>
 * 一次发药在同一事务内完成：收费明细“已发数量”条件更新 → 锁定转实扣（按批次 id 升序）→ 写追溯码。
 * 收费明细的条件更新防止多发和重复点击；追溯码唯一索引防止同一盒药被卖两次（串换、回流药）。
 */
@Service
@RequiredArgsConstructor
public class DispenseService {

    /** 药品追溯码：20 位数字（最小销售包装） */
    private static final Pattern TRACE_CODE = Pattern.compile("\\d{20}");

    private final DispenseOrderPort orderPort;
    private final StockService stockService;
    private final DrugService drugService;
    private final StockLockMapper lockMapper;
    private final DrugBatchMapper batchMapper;
    private final DrugTraceCodeMapper traceMapper;
    private final PatientQueryApi patientQueryApi;
    private final TransactionTemplate transactionTemplate;

    public DispenseOrderVO view(String chargeNo) {
        DispenseOrder order = requireOrder(chargeNo);
        Map<String, Drug> drugs = drugService.findByCodes(order.items().stream().map(DispenseItem::itemCode).toList());
        List<DispenseOrderVO.Item> items = new ArrayList<>();
        for (DispenseItem item : order.items()) {
            List<StockLock> locks = lockMapper.selectList(Wrappers.<StockLock>lambdaQuery()
                    .eq(StockLock::getChargeItemId, item.chargeItemId()).orderByAsc(StockLock::getId));
            Map<Long, DrugBatch> batches = locks.isEmpty() ? Map.of() : batchMapper.selectByIds(
                    locks.stream().map(StockLock::getBatchId).toList()).stream()
                    .collect(Collectors.toMap(DrugBatch::getId, Function.identity()));
            Drug drug = drugs.get(item.itemCode());
            items.add(new DispenseOrderVO.Item(item.chargeItemId(), item.itemCode(), item.itemName(), item.qty(),
                    item.dispensedQty(), item.refundedQty(), item.dispensable(),
                    drug != null && drug.getTraceRequired() == 1,
                    locks.stream().map(l -> new DispenseOrderVO.Lock(batches.get(l.getBatchId()).getBatchNo(),
                            batches.get(l.getBatchId()).getExpireDate(), l.lockedRemaining(), l.getDispensedQty(),
                            l.getReturnedQty())).toList()));
        }
        String name = patientQueryApi.names(List.of(order.patientId())).get(order.patientId());
        return new DispenseOrderVO(order.chargeNo(), name, items);
    }

    public void dispense(DispenseDTO dto) {
        DispenseOrder order = requireOrder(dto.chargeNo());
        Map<Long, DispenseItem> items = order.items().stream()
                .collect(Collectors.toMap(DispenseItem::chargeItemId, Function.identity()));
        Map<String, Drug> drugs = drugService.findByCodes(order.items().stream().map(DispenseItem::itemCode).toList());

        Set<String> seenCodes = new HashSet<>();
        List<DispenseDTO.Line> lines = dto.items().stream()
                .sorted(Comparator.comparing(DispenseDTO.Line::chargeItemId)).toList();
        for (DispenseDTO.Line line : lines) {
            DispenseItem item = items.get(line.chargeItemId());
            BizException.throwIf(item == null, PharmacyErrorCode.ITEM_NOT_IN_ORDER);
            BizException.throwIf(line.qty() > item.dispensable(), PharmacyErrorCode.DISPENSE_QTY_EXCEEDED);
            if (traceRequired(drugs, item)) {
                checkTraceCodes(line.traceCodes(), line.qty(), seenCodes);
            }
        }

        try {
            transactionTemplate.executeWithoutResult(tx -> {
                for (DispenseDTO.Line line : lines) {
                    // 以收费明细的条件更新为准：并发发药、重复点击、退费处理中都会在这里失败
                    BizException.throwIf(!orderPort.markDispensed(line.chargeItemId(), line.qty()),
                            PharmacyErrorCode.DISPENSE_QTY_EXCEEDED, "该药品已发药或已申请退费，请刷新");
                }
                List<Allocation> all = new ArrayList<>();
                List<DrugTraceCode> traces = new ArrayList<>();
                for (DispenseDTO.Line line : lines) {
                    List<Allocation> plan = stockService.planDispense(line.chargeItemId(), line.qty());
                    all.addAll(plan);
                    DispenseItem item = items.get(line.chargeItemId());
                    if (traceRequired(drugs, item)) {
                        traces.addAll(assignTraceCodes(line.traceCodes(), plan, drugs.get(item.itemCode()).getId()));
                    }
                }
                stockService.applyDispense(all, order.chargeNo());
                traces.forEach(traceMapper::insert);
            });
        } catch (DuplicateKeyException e) {
            throw new BizException(PharmacyErrorCode.TRACE_CODE_USED);
        }
    }

    public void returnDrug(ReturnDTO dto) {
        DispenseOrder order = requireOrder(dto.chargeNo());
        DispenseItem item = order.items().stream().filter(i -> i.chargeItemId().equals(dto.chargeItemId()))
                .findFirst().orElseThrow(() -> new BizException(PharmacyErrorCode.ITEM_NOT_IN_ORDER));
        BizException.throwIf(dto.qty() > item.dispensedQty(), PharmacyErrorCode.RETURN_QTY_EXCEEDED);
        Map<String, Drug> drugs = drugService.findByCodes(List.of(item.itemCode()));
        boolean traced = traceRequired(drugs, item);
        if (traced) {
            checkTraceCodes(dto.traceCodes(), dto.qty(), new HashSet<>());
        }

        transactionTemplate.executeWithoutResult(tx -> {
            BizException.throwIf(!orderPort.markReturned(item.chargeItemId(), dto.qty()),
                    PharmacyErrorCode.RETURN_QTY_EXCEEDED);
            List<Allocation> plan;
            if (traced) {
                plan = planReturnByTraceCodes(item.chargeItemId(), dto.traceCodes());
            } else {
                plan = stockService.planReturn(item.chargeItemId(), dto.qty());
            }
            stockService.applyReturn(plan, order.chargeNo());
        });
    }

    /** 按扫到的追溯码定位退回到哪个批次：码在发药时就记录了所属的锁定记录 */
    private List<Allocation> planReturnByTraceCodes(Long chargeItemId, List<String> codes) {
        Map<Long, StockLock> locks = stockService.locksForUpdate(chargeItemId).stream()
                .collect(Collectors.toMap(StockLock::getId, Function.identity()));
        List<DrugTraceCode> traces = traceMapper.selectList(Wrappers.<DrugTraceCode>lambdaQuery()
                .eq(DrugTraceCode::getChargeItemId, chargeItemId)
                .in(DrugTraceCode::getTraceCode, codes)
                .ne(DrugTraceCode::getStatus, DrugTraceCode.RETURNED));
        BizException.throwIf(traces.size() != codes.size(), PharmacyErrorCode.TRACE_CODE_NOT_FOUND);
        Map<Long, Integer> perLock = new LinkedHashMap<>();
        for (DrugTraceCode t : traces) {
            BizException.throwIf(traceMapper.markReturned(t.getId()) == 0, PharmacyErrorCode.TRACE_CODE_NOT_FOUND);
            perLock.merge(t.getStockLockId(), 1, Integer::sum);
        }
        List<Allocation> plan = new ArrayList<>();
        perLock.forEach((lockId, n) -> plan.add(new Allocation(chargeItemId, lockId, locks.get(lockId).getBatchId(), n)));
        return plan;
    }

    /** 扫码顺序与发药分配顺序对应：前几盒记在第一个批次，依此类推 */
    private static List<DrugTraceCode> assignTraceCodes(List<String> codes, List<Allocation> plan, Long drugId) {
        List<DrugTraceCode> result = new ArrayList<>(codes.size());
        int index = 0;
        for (Allocation a : plan) {
            for (int i = 0; i < a.qty(); i++) {
                DrugTraceCode t = new DrugTraceCode();
                t.setTraceCode(codes.get(index++));
                t.setDrugId(drugId);
                t.setBatchId(a.batchId());
                t.setChargeItemId(a.chargeItemId());
                t.setStockLockId(a.lockId());
                t.setStatus(DrugTraceCode.COLLECTED);
                t.setRetryCount(0);
                result.add(t);
            }
        }
        return result;
    }

    private static void checkTraceCodes(List<String> codes, int qty, Set<String> seen) {
        BizException.throwIf(codes == null || codes.size() != qty, PharmacyErrorCode.TRACE_CODE_REQUIRED);
        for (String code : codes) {
            BizException.throwIf(code == null || !TRACE_CODE.matcher(code).matches(),
                    PharmacyErrorCode.TRACE_CODE_INVALID);
            BizException.throwIf(!seen.add(code), PharmacyErrorCode.TRACE_CODE_USED, "同一追溯码扫了两次：" + code);
        }
    }

    private static boolean traceRequired(Map<String, Drug> drugs, DispenseItem item) {
        Drug drug = drugs.get(item.itemCode());
        return drug != null && drug.getTraceRequired() == 1;
    }

    private DispenseOrder requireOrder(String chargeNo) {
        return orderPort.findPaidOrder(chargeNo)
                .orElseThrow(() -> new BizException(PharmacyErrorCode.ORDER_NOT_DISPENSABLE));
    }
}
