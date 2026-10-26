package io.github.ryancamus.clinic.pharmacy.api;

import java.util.List;

/**
 * 库存锁定与释放，供收费模块在开单、关单、退费时调用。必须在调用方的事务内执行。
 * <p>
 * 划价时锁定（qty_locked 增加，在库不变），发药时锁定转为实扣，关单或退费时释放。
 * 这样“已开单未发药”的药不会被别人开走，也不会在患者付款前就扣减在库。
 */
public interface StockApi {

    /**
     * 按“先到期先出”为多条明细锁定库存。一次调用内所有批次按 id 升序加锁，避免与其他事务死锁。
     *
     * @throws io.github.ryancamus.clinic.common.exception.BizException 可用库存不足
     */
    void lock(LockRequest request);

    /**
     * 把收费明细仍在锁定中的数量释放到 keepLocked。幂等：已经不超过 keepLocked 时什么也不做。
     * 关单时 keepLocked = 0；退费成功时 keepLocked = 尚待发药的数量。多条明细一次传入，批次统一按 id 升序更新。
     */
    void releaseDownTo(List<ReleaseLine> lines, String bizNo);

    record ReleaseLine(Long chargeItemId, int keepLocked) {
    }

    record LockRequest(Long campusId, String bizNo, List<LockLine> lines) {
    }

    record LockLine(Long chargeItemId, Long drugId, int qty) {
    }
}
