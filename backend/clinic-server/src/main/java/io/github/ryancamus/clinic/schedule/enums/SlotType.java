package io.github.ryancamus.clinic.schedule.enums;

/**
 * 号源类型。三类共用一套号源与预约逻辑，区别只在价目和放号方式。
 */
public enum SlotType {

    /** 门诊：量小，日常 */
    OUTPATIENT(1),
    /** 新生入学体检：人数多、集中，按校区和时段预约 */
    PHYSICAL_EXAM(2),
    /** 疫苗：名额少、定时放号，最典型的抢约场景 */
    VACCINE(3);

    private final int code;

    SlotType(int code) {
        this.code = code;
    }

    public int code() {
        return code;
    }

    public static boolean isValid(Integer code) {
        if (code == null) {
            return false;
        }
        for (SlotType type : values()) {
            if (type.code == code) {
                return true;
            }
        }
        return false;
    }
}
