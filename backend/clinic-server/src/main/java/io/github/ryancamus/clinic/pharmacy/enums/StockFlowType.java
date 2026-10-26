package io.github.ryancamus.clinic.pharmacy.enums;

/**
 * 库存流水类型。每种操作对 在库 / 锁定 的影响：
 * <pre>
 *   入库  在库 +n
 *   锁定           锁定 +n
 *   释放           锁定 −n
 *   发药  在库 −n  锁定 −n
 *   退药  在库 +n
 * </pre>
 * 任意时刻：批次在库 = Σ 流水.在库变化，批次锁定 = Σ 流水.锁定变化。
 */
public final class StockFlowType {

    public static final int IN = 1;
    public static final int LOCK = 2;
    public static final int UNLOCK = 3;
    public static final int OUT = 4;
    public static final int RETURN = 5;
    public static final int ADJUST = 6;

    /** 流水的业务单据类型 */
    public static final int BIZ_INBOUND = 1;
    public static final int BIZ_CHARGE = 2;

    private StockFlowType() {
    }
}
