package io.github.ryancamus.clinic.charge.enums;

/**
 * 收费明细的项目类型（charge_order_item.item_type），也是资金流水、报表的收入科目维度。
 */
public final class ChargeItemType {

    public static final int REGISTRATION = 1;
    public static final int DRUG = 2;
    public static final int TREATMENT = 3;
    public static final int EXAMINATION = 4;
    public static final int MATERIAL = 5;
    public static final int PHYSICAL_EXAM = 6;
    public static final int VACCINE_SERVICE = 7;

    private ChargeItemType() {
    }

    /** 药品、卫生材料：开单锁库存、由药房发放 */
    public static boolean isGoods(int itemType) {
        return itemType == DRUG || itemType == MATERIAL;
    }
}
