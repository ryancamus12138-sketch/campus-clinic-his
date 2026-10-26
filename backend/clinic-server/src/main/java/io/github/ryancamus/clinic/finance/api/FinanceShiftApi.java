package io.github.ryancamus.clinic.finance.api;

/**
 * 收费员班次。窗口收费、退费记入办理人当班的班次，交班时按班次汇总流水。
 */
public interface FinanceShiftApi {

    /**
     * 返回收费员当前未交班的班次，没有则自动开班。并发调用只会开出一个班（唯一约束兜底）。
     */
    Long ensureOpenShift(Long cashierId, Long campusId);

    /**
     * 某个班次的收费员现在的当班班次：原班次未交班则就是它，已交班则返回（必要时开出）该收费员的新班次。
     * 用于需要审批的退费：申请时记下办理人的班次，审批通过、实际退款时办理人可能已经交班。
     */
    Long currentShiftOf(Long shiftId);
}
