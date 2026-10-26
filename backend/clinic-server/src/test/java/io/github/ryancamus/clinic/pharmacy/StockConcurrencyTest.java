package io.github.ryancamus.clinic.pharmacy;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import io.github.ryancamus.clinic.common.exception.BizException;
import io.github.ryancamus.clinic.common.money.Money;
import io.github.ryancamus.clinic.pharmacy.api.StockApi;
import io.github.ryancamus.clinic.pharmacy.domain.entity.Drug;
import io.github.ryancamus.clinic.pharmacy.domain.entity.DrugBatch;
import io.github.ryancamus.clinic.pharmacy.domain.entity.StockFlow;
import io.github.ryancamus.clinic.pharmacy.domain.entity.StockLock;
import io.github.ryancamus.clinic.pharmacy.mapper.DrugBatchMapper;
import io.github.ryancamus.clinic.pharmacy.mapper.DrugMapper;
import io.github.ryancamus.clinic.pharmacy.mapper.StockFlowMapper;
import io.github.ryancamus.clinic.pharmacy.mapper.StockLockMapper;
import io.github.ryancamus.clinic.pharmacy.service.StockService.Allocation;
import io.github.ryancamus.clinic.pharmacy.service.StockService;
import io.github.ryancamus.clinic.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M6：锁定、发药、退药、释放混合 1 万次以上并发操作。
 * <p>
 * 每个“处方”同时开两种药（顺序随机，模拟不同医生的开方习惯），两种药各有 3 个批次，
 * 锁定量跨批次拆分。20 个线程（占满连接池）同时操作同一组批次行。断言：
 * <ul>
 *   <li>死锁 0、锁等待超时 0（加锁顺序固定）；</li>
 *   <li>负库存 0（CHECK 约束，违反会直接报错）；</li>
 *   <li>账实一致：每个批次 在库 = Σ流水.在库变化，锁定 = Σ流水.锁定变化 = Σ锁定记录的锁定中数量；</li>
 *   <li>在库 = 入库 − Σ(发药 − 退药)。</li>
 * </ul>
 */
@IntegrationTest
class StockConcurrencyTest {

    private static final int ORDERS = 4_500;
    /** 与连接池大小（20）一致：再多的线程只会在连接池上排队，不会增加数据库层的并发 */
    private static final int THREADS = 20;
    private static final int BATCH_QTY = 6_000;

    @Autowired
    private StockService stockService;
    @Autowired
    private DrugMapper drugMapper;
    @Autowired
    private DrugBatchMapper batchMapper;
    @Autowired
    private StockLockMapper lockMapper;
    @Autowired
    private StockFlowMapper flowMapper;
    @Autowired
    private TransactionTemplate transactionTemplate;

    @Test
    void tenThousandMixedOperationsKeepBooksAndStockInSync() throws Exception {
        Long campusId = 2L;
        Long drugA = newDrug("M6A");
        Long drugB = newDrug("M6B");
        List<Long> batches = new ArrayList<>();
        for (Long drug : List.of(drugA, drugB)) {
            for (int i = 0; i < 3; i++) {
                batches.add(stockService.inbound(campusId, drug, "M6" + IdWorker.getIdStr().substring(10) + i,
                        LocalDate.now().plusYears(1).plusMonths(i), new BigDecimal("1.00"), BATCH_QTY));
            }
        }

        AtomicInteger ops = new AtomicInteger();
        AtomicInteger deadlocks = new AtomicInteger();
        AtomicInteger insufficient = new AtomicInteger();
        Map<String, Integer> unexpected = new ConcurrentHashMap<>();
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        long begin;
        try (ExecutorService pool = Executors.newFixedThreadPool(THREADS)) {
            for (int i = 0; i < ORDERS; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    runOrder(campusId, drugA, drugB, ops, deadlocks, insufficient, unexpected);
                    return null;
                }));
            }
            begin = System.nanoTime();
            start.countDown();
            for (Future<?> f : futures) {
                f.get(5, TimeUnit.MINUTES);
            }
        }
        long elapsed = (System.nanoTime() - begin) / 1_000_000;

        int mismatches = 0;
        for (Long batchId : batches) {
            DrugBatch b = batchMapper.selectById(batchId);
            List<StockFlow> flows = flowMapper.selectList(Wrappers.<StockFlow>lambdaQuery()
                    .eq(StockFlow::getBatchId, batchId));
            List<StockLock> locks = lockMapper.selectList(Wrappers.<StockLock>lambdaQuery()
                    .eq(StockLock::getBatchId, batchId));
            int flowOnHand = flows.stream().mapToInt(StockFlow::getDeltaOnHand).sum();
            int flowLocked = flows.stream().mapToInt(StockFlow::getDeltaLocked).sum();
            int lockedByRecords = locks.stream().mapToInt(StockLock::lockedRemaining).sum();
            int netOut = locks.stream().mapToInt(StockLock::outstanding).sum();
            if (b.getQtyOnHand() != flowOnHand || b.getQtyLocked() != flowLocked
                    || b.getQtyLocked() != lockedByRecords || b.getQtyOnHand() != BATCH_QTY - netOut) {
                mismatches++;
            }
            assertThat(b.getQtyOnHand()).as("在库 = Σ流水").isEqualTo(flowOnHand);
            assertThat(b.getQtyLocked()).as("锁定 = Σ流水").isEqualTo(flowLocked);
            assertThat(b.getQtyLocked()).as("锁定 = Σ锁定记录").isEqualTo(lockedByRecords);
            assertThat(b.getQtyOnHand()).as("在库 = 入库 − 净发出").isEqualTo(BATCH_QTY - netOut);
            assertThat(b.getQtyOnHand()).isGreaterThanOrEqualTo(b.getQtyLocked()).isGreaterThanOrEqualTo(0);
        }

        System.out.printf("[M6] ops=%d orders=%d threads=%d elapsed=%dms deadlocks=%d insufficient=%d "
                        + "mismatches=%d unexpected=%s%n", ops.get(), ORDERS, THREADS, elapsed, deadlocks.get(),
                insufficient.get(), mismatches, unexpected);
        assertThat(ops.get()).isGreaterThanOrEqualTo(10_000);
        assertThat(deadlocks.get()).as("死锁 0").isZero();
        assertThat(unexpected).as("没有其他异常").isEmpty();
    }

    /** 一张处方的生命周期：锁定 → 释放；或 锁定 → 发药（全部或部分）→ 部分退药 → 释放剩余 */
    private void runOrder(Long campusId, Long drugA, Long drugB, AtomicInteger ops, AtomicInteger deadlocks,
                          AtomicInteger insufficient, Map<String, Integer> unexpected) {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        long itemA = IdWorker.getId();
        long itemB = IdWorker.getId();
        int qtyA = 1 + r.nextInt(5);
        int qtyB = 1 + r.nextInt(5);
        List<StockApi.LockLine> lines = new ArrayList<>(List.of(
                new StockApi.LockLine(itemA, drugA, qtyA), new StockApi.LockLine(itemB, drugB, qtyB)));
        if (r.nextBoolean()) {
            Collections.reverse(lines);
        }
        String bizNo = "M6" + itemA;
        if (!step(ops, deadlocks, insufficient, unexpected,
                () -> stockService.lock(new StockApi.LockRequest(campusId, bizNo, lines)))) {
            return;
        }
        if (r.nextInt(10) < 3) {
            step(ops, deadlocks, insufficient, unexpected, () -> stockService.releaseDownTo(List.of(
                    new StockApi.ReleaseLine(itemA, 0), new StockApi.ReleaseLine(itemB, 0)), bizNo));
            return;
        }
        int dispenseA = 1 + r.nextInt(qtyA);
        int dispenseB = 1 + r.nextInt(qtyB);
        if (!step(ops, deadlocks, insufficient, unexpected, () -> transactionTemplate.executeWithoutResult(tx -> {
            List<Allocation> plan = new ArrayList<>(stockService.planDispense(itemA, dispenseA));
            plan.addAll(stockService.planDispense(itemB, dispenseB));
            stockService.applyDispense(plan, bizNo);
        }))) {
            return;
        }
        if (r.nextBoolean()) {
            int back = 1 + r.nextInt(dispenseA);
            step(ops, deadlocks, insufficient, unexpected, () -> transactionTemplate.executeWithoutResult(tx ->
                    stockService.applyReturn(stockService.planReturn(itemA, back), bizNo)));
        }
        if (dispenseA < qtyA || dispenseB < qtyB) {
            step(ops, deadlocks, insufficient, unexpected, () -> stockService.releaseDownTo(List.of(
                    new StockApi.ReleaseLine(itemA, 0), new StockApi.ReleaseLine(itemB, 0)), bizNo));
        }
    }

    private static boolean step(AtomicInteger ops, AtomicInteger deadlocks, AtomicInteger insufficient,
                                Map<String, Integer> unexpected, Runnable action) {
        ops.incrementAndGet();
        try {
            action.run();
            return true;
        } catch (PessimisticLockingFailureException e) {
            // 死锁牺牲者与锁等待超时都归入这里
            deadlocks.incrementAndGet();
        } catch (BizException e) {
            insufficient.incrementAndGet();
        } catch (RuntimeException e) {
            unexpected.merge(e.getClass().getSimpleName() + ": " + e.getMessage(), 1, Integer::sum);
        }
        return false;
    }

    private Long newDrug(String prefix) {
        Drug d = new Drug();
        d.setDrugCode(prefix + IdWorker.getIdStr().substring(8));
        d.setName("压测药品" + prefix);
        d.setSpec("1盒");
        d.setUnit("盒");
        d.setGoodsType(1);
        d.setRetailPrice(Money.of("1.00"));
        d.setInsuranceClass(1);
        d.setTraceRequired(0);
        d.setSafetyStock(0);
        d.setStatus(1);
        drugMapper.insert(d);
        return d.getId();
    }
}
