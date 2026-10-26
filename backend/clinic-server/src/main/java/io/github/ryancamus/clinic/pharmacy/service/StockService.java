package io.github.ryancamus.clinic.pharmacy.service;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import io.github.ryancamus.clinic.common.exception.BizException;
import io.github.ryancamus.clinic.framework.security.SecurityUtils;
import io.github.ryancamus.clinic.pharmacy.api.StockApi;
import io.github.ryancamus.clinic.pharmacy.domain.entity.DrugBatch;
import io.github.ryancamus.clinic.pharmacy.domain.entity.StockFlow;
import io.github.ryancamus.clinic.pharmacy.domain.entity.StockLock;
import io.github.ryancamus.clinic.pharmacy.enums.PharmacyErrorCode;
import io.github.ryancamus.clinic.pharmacy.enums.StockFlowType;
import io.github.ryancamus.clinic.pharmacy.mapper.DrugBatchMapper;
import io.github.ryancamus.clinic.pharmacy.mapper.StockFlowMapper;
import io.github.ryancamus.clinic.pharmacy.mapper.StockLockMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;

/**
 * 批次库存的锁定、发药、退药、释放。
 * <p>
 * 防死锁的加锁顺序在所有操作中保持一致：收费明细 → 锁定记录（按 id）→ 批次（按 id 升序）。
 * 一次操作涉及多个批次时，先算出每个批次的变化量，再统一按批次 id 升序执行，
 * 所有事务以相同顺序获取行锁，不会形成环形等待。
 * <p>
 * 每次变化都写一条流水（变化量 + 变化后余额），批次当前量恒等于其流水之和。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StockService implements StockApi {

    /** 30 天内到期的批次不再用于发药 */
    static final int NEAR_EXPIRY_DAYS = 30;

    private final DrugBatchMapper batchMapper;
    private final StockLockMapper lockMapper;
    private final StockFlowMapper flowMapper;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;

    /** 一次分配：从某条锁定记录（批次）上取 qty */
    public record Allocation(Long chargeItemId, Long lockId, Long batchId, int qty) {
    }

    // ------------------------------------------------------------------ 锁定

    @Override
    public void lock(LockRequest request) {
        transactionTemplate.executeWithoutResult(tx -> doLock(request));
    }

    /**
     * 先按 id 升序逐行锁住候选批次，再基于锁住后的最新数量分配。
     * <p>
     * 一个事务内对批次的加锁严格按 id 递增，所有事务的加锁顺序一致，不会互相等待形成死锁；
     * 分配基于加锁后的数据，不需要“条件更新失败再重试”。
     */
    private void doLock(LockRequest request) {
        List<LockLine> lines = request.lines().stream().filter(l -> l.qty() > 0).toList();
        if (lines.isEmpty()) {
            return;
        }
        List<Long> drugIds = lines.stream().map(LockLine::drugId).distinct().toList();
        LocalDate minExpire = LocalDate.now(clock).plusDays(NEAR_EXPIRY_DAYS);
        Map<Long, List<DrugBatch>> byDrug = new HashMap<>();
        for (Long batchId : batchMapper.selectCandidateIds(request.campusId(), drugIds, minExpire)) {
            DrugBatch b = batchMapper.selectForUpdate(batchId);
            if (b.available() > 0) {
                byDrug.computeIfAbsent(b.getDrugId(), k -> new ArrayList<>()).add(b);
            }
        }
        byDrug.values().forEach(list -> list.sort(
                Comparator.comparing(DrugBatch::getExpireDate).thenComparing(DrugBatch::getId)));

        List<Allocation> plan = planLock(lines, byDrug);
        Map<Long, Integer> perBatch = sumByBatch(plan);
        for (Map.Entry<Long, Integer> e : perBatch.entrySet()) {
            if (batchMapper.lockQty(e.getKey(), e.getValue()) == 0) {
                throw new IllegalStateException("批次已加锁但可用量不足 batchId=" + e.getKey());
            }
        }
        for (Allocation a : plan) {
            StockLock lock = new StockLock();
            lock.setChargeItemId(a.chargeItemId());
            lock.setBatchId(a.batchId());
            lock.setQty(a.qty());
            lock.setDispensedQty(0);
            lock.setReleasedQty(0);
            lock.setReturnedQty(0);
            lock.setStatus(StockLock.LOCKED);
            lockMapper.insert(lock);
        }
        perBatch.forEach((batchId, qty) -> flow(batchId, StockFlowType.LOCK, 0, qty, request.bizNo()));
    }

    /** 先到期先出：同一药品的多条明细共享批次可用量 */
    private static List<Allocation> planLock(List<LockLine> lines, Map<Long, List<DrugBatch>> byDrug) {
        Map<Long, Integer> available = new HashMap<>();
        byDrug.values().forEach(list -> list.forEach(b -> available.put(b.getId(), b.available())));
        List<Allocation> plan = new ArrayList<>();
        for (LockLine line : lines) {
            int need = line.qty();
            for (DrugBatch b : byDrug.getOrDefault(line.drugId(), List.of())) {
                int take = Math.min(need, available.get(b.getId()));
                if (take > 0) {
                    plan.add(new Allocation(line.chargeItemId(), null, b.getId(), take));
                    available.merge(b.getId(), -take, Integer::sum);
                    need -= take;
                }
                if (need == 0) {
                    break;
                }
            }
            if (need > 0) {
                throw new BizException(PharmacyErrorCode.STOCK_INSUFFICIENT);
            }
        }
        return plan;
    }

    // ------------------------------------------------------------------ 释放

    @Override
    public void releaseDownTo(List<ReleaseLine> lines, String bizNo) {
        transactionTemplate.executeWithoutResult(tx -> {
            List<Allocation> plan = new ArrayList<>();
            for (ReleaseLine line : sortedById(lines, ReleaseLine::chargeItemId)) {
                List<StockLock> locks = lockMapper.selectForUpdate(line.chargeItemId());
                int excess = locks.stream().mapToInt(StockLock::lockedRemaining).sum() - line.keepLocked();
                // 后锁定的（效期较晚的）先释放，留下先到期的批次给发药
                for (int i = locks.size() - 1; i >= 0 && excess > 0; i--) {
                    StockLock l = locks.get(i);
                    int take = Math.min(excess, l.lockedRemaining());
                    if (take > 0) {
                        plan.add(new Allocation(line.chargeItemId(), l.getId(), l.getBatchId(), take));
                        excess -= take;
                    }
                }
            }
            apply(plan, bizNo, StockFlowType.UNLOCK);
        });
    }

    // ------------------------------------------------------------------ 发药、退药

    /** 为一条明细分配发药数量：按锁定顺序（即先到期先出）从各批次取 */
    public List<Allocation> planDispense(Long chargeItemId, int qty) {
        List<Allocation> plan = new ArrayList<>();
        int need = qty;
        for (StockLock l : lockMapper.selectForUpdate(chargeItemId)) {
            int take = Math.min(need, l.lockedRemaining());
            if (take > 0) {
                plan.add(new Allocation(chargeItemId, l.getId(), l.getBatchId(), take));
                need -= take;
            }
        }
        BizException.throwIf(need > 0, PharmacyErrorCode.DISPENSE_QTY_EXCEEDED, "锁定库存不足以发药，请联系管理员核对");
        return plan;
    }

    /** 没有追溯码的退药：从后发出的批次开始退 */
    public List<Allocation> planReturn(Long chargeItemId, int qty) {
        List<StockLock> locks = lockMapper.selectForUpdate(chargeItemId);
        List<Allocation> plan = new ArrayList<>();
        int need = qty;
        for (int i = locks.size() - 1; i >= 0 && need > 0; i--) {
            StockLock l = locks.get(i);
            int take = Math.min(need, l.outstanding());
            if (take > 0) {
                plan.add(new Allocation(chargeItemId, l.getId(), l.getBatchId(), take));
                need -= take;
            }
        }
        BizException.throwIf(need > 0, PharmacyErrorCode.RETURN_QTY_EXCEEDED);
        return plan;
    }

    /** 锁住明细的锁定记录并返回，供按追溯码定位批次 */
    public List<StockLock> locksForUpdate(Long chargeItemId) {
        return lockMapper.selectForUpdate(chargeItemId);
    }

    public void applyDispense(List<Allocation> plan, String bizNo) {
        apply(plan, bizNo, StockFlowType.OUT);
    }

    public void applyReturn(List<Allocation> plan, String bizNo) {
        apply(plan, bizNo, StockFlowType.RETURN);
    }

    /**
     * 执行分配：锁定记录逐条更新，批次按 id 升序合并更新并记流水。
     * 任何一步条件不满足都抛出异常回滚整个事务，不会留下“记录改了、批次没改”的半成品。
     */
    private void apply(List<Allocation> plan, String bizNo, int flowType) {
        if (plan.isEmpty()) {
            return;
        }
        for (Allocation a : sortedById(plan, Allocation::lockId)) {
            int rows = switch (flowType) {
                case StockFlowType.OUT -> lockMapper.dispense(a.lockId(), a.qty());
                case StockFlowType.RETURN -> lockMapper.returnQty(a.lockId(), a.qty());
                case StockFlowType.UNLOCK -> lockMapper.release(a.lockId(), a.qty());
                default -> throw new IllegalArgumentException("flowType " + flowType);
            };
            if (rows == 0) {
                throw new IllegalStateException("锁定记录数量不足 lockId=" + a.lockId());
            }
        }
        for (Map.Entry<Long, Integer> e : sumByBatch(plan).entrySet()) {
            Long batchId = e.getKey();
            int qty = e.getValue();
            int rows = switch (flowType) {
                case StockFlowType.OUT -> batchMapper.dispenseQty(batchId, qty);
                case StockFlowType.RETURN -> batchMapper.returnQty(batchId, qty);
                default -> batchMapper.unlockQty(batchId, qty);
            };
            if (rows == 0) {
                throw new IllegalStateException("批次库存与锁定记录不一致 batchId=" + batchId);
            }
            switch (flowType) {
                case StockFlowType.OUT -> flow(batchId, flowType, -qty, -qty, bizNo);
                case StockFlowType.RETURN -> flow(batchId, flowType, qty, 0, bizNo);
                default -> flow(batchId, flowType, 0, -qty, bizNo);
            }
        }
    }

    // ------------------------------------------------------------------ 入库

    /**
     * 采购入库：同院区同药品同批号累加在库量。
     *
     * @return 批次 ID
     */
    public Long inbound(Long campusId, Long drugId, String batchNo, LocalDate expireDate,
                        BigDecimal purchasePrice, int qty) {
        BizException.throwIf(qty <= 0 || !expireDate.isAfter(LocalDate.now(clock)), PharmacyErrorCode.INVALID_BATCH);
        String bizNo = "I" + IdWorker.getIdStr();
        return transactionTemplate.execute(tx -> {
            batchMapper.upsertInbound(IdWorker.getId(), campusId, drugId, batchNo, expireDate, purchasePrice, qty,
                    operator());
            DrugBatch batch = batchMapper.selectOne(Wrappers.<DrugBatch>lambdaQuery()
                    .eq(DrugBatch::getCampusId, campusId)
                    .eq(DrugBatch::getDrugId, drugId)
                    .eq(DrugBatch::getBatchNo, batchNo));
            BizException.throwIf(!batch.getExpireDate().equals(expireDate), PharmacyErrorCode.INVALID_BATCH,
                    "同一批号的效期与已入库记录不一致");
            flow(batch.getId(), StockFlowType.IN, qty, 0, bizNo, StockFlowType.BIZ_INBOUND);
            return batch.getId();
        });
    }

    // ------------------------------------------------------------------ 内部方法

    private void flow(Long batchId, int type, int deltaOnHand, int deltaLocked, String bizNo) {
        flow(batchId, type, deltaOnHand, deltaLocked, bizNo, StockFlowType.BIZ_CHARGE);
    }

    private void flow(Long batchId, int type, int deltaOnHand, int deltaLocked, String bizNo, int bizType) {
        // 本事务已持有该批次行锁，读到的就是本次变化后的余额
        DrugBatch batch = batchMapper.selectById(batchId);
        StockFlow f = new StockFlow();
        f.setId(IdWorker.getId());
        f.setBatchId(batchId);
        f.setDrugId(batch.getDrugId());
        f.setFlowType(type);
        f.setDeltaOnHand(deltaOnHand);
        f.setDeltaLocked(deltaLocked);
        f.setAfterOnHand(batch.getQtyOnHand());
        f.setAfterLocked(batch.getQtyLocked());
        f.setBizType(bizType);
        f.setBizNo(bizNo);
        f.setOperatorId(operator());
        f.setCreateTime(LocalDateTime.now(clock));
        flowMapper.insert(f);
    }

    private static Map<Long, Integer> sumByBatch(List<Allocation> plan) {
        Map<Long, Integer> perBatch = new TreeMap<>();
        plan.forEach(a -> perBatch.merge(a.batchId(), a.qty(), Integer::sum));
        return perBatch;
    }

    private static <T> List<T> sortedById(List<T> list, Function<T, Long> id) {
        return list.stream().sorted(Comparator.comparing(id)).toList();
    }

    private static Long operator() {
        Long id = SecurityUtils.currentUserIdOrNull();
        return id == null ? 0L : id;
    }
}
