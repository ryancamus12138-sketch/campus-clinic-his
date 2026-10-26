package io.github.ryancamus.clinic.system.api;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 系统模块对外提供的组织信息查询（科室名、员工姓名、收费点）。
 */
public interface OrgQueryApi {

    Map<Long, String> deptNames(Collection<Long> deptIds);

    Map<Long, String> userNicknames(Collection<Long> userIds);

    boolean deptExists(Long campusId, Long deptId);

    /** 校区的默认收费点（启用的、排序最前的），没有配置时为空 */
    Optional<ChargePointInfo> defaultChargePoint(Long campusId);

    Map<Long, String> chargePointNames(Collection<Long> ids);

    /**
     * @param counterMethods 开放的窗口收款方式：CASH、BANK_CARD、CAMPUS_CARD、AUTH_CODE
     */
    record ChargePointInfo(Long id, String name, Set<String> counterMethods) {
    }
}
