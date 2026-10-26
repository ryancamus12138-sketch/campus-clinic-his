package io.github.ryancamus.clinic.system.domain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import io.github.ryancamus.clinic.framework.mybatis.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@TableName("sys_user")
public class SysUser extends BaseEntity {

    public static final int STATUS_ENABLED = 1;
    public static final int TYPE_STAFF = 1;

    private String username;
    private String passwordHash;
    private String nickname;
    /** 1 员工（患者不登录本系统，取值 2 已停用） */
    private Integer userType;
    private Long campusId;
    private Long deptId;
    /** 1 启用 0 停用 */
    private Integer status;
    private LocalDateTime lastLoginTime;
    /** 口令最近一次修改时间 */
    private LocalDateTime passwordChangedAt;
    /** 1 下次登录必须修改口令（新建账号、管理员重置后） */
    private Integer mustChangePassword;

    public boolean isStaff() {
        return userType != null && userType == TYPE_STAFF;
    }

    public boolean isEnabled() {
        return status != null && status == STATUS_ENABLED;
    }
}
