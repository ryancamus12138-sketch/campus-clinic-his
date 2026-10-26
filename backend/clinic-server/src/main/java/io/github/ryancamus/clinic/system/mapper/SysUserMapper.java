package io.github.ryancamus.clinic.system.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.github.ryancamus.clinic.system.domain.entity.SysUser;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

public interface SysUserMapper extends BaseMapper<SysUser> {

    @Select("""
            SELECT r.role_code
            FROM sys_user_role ur
            JOIN sys_role r ON r.id = ur.role_id AND r.status = 1
            WHERE ur.user_id = #{userId}
            """)
    List<String> selectRoleCodes(@Param("userId") Long userId);

    @Select("""
            SELECT DISTINCT p.perm_code
            FROM sys_user_role ur
            JOIN sys_role r ON r.id = ur.role_id AND r.status = 1
            JOIN sys_role_permission rp ON rp.role_id = r.id
            JOIN sys_permission p ON p.id = rp.permission_id
            WHERE ur.user_id = #{userId}
            """)
    List<String> selectPermissionCodes(@Param("userId") Long userId);

    @Select("""
            <script>
            SELECT u.* FROM sys_user u
            JOIN sys_user_role ur ON ur.user_id = u.id
            JOIN sys_role r ON r.id = ur.role_id
            WHERE r.role_code = #{roleCode} AND u.status = 1
            <if test="deptId != null">AND u.dept_id = #{deptId}</if>
            ORDER BY u.id
            </script>
            """)
    List<SysUser> selectByRole(@Param("roleCode") String roleCode, @Param("deptId") Long deptId);

    @Insert("INSERT INTO sys_user_role (user_id, role_id) VALUES (#{userId}, #{roleId})")
    int insertUserRole(@Param("userId") Long userId, @Param("roleId") Long roleId);

    /** 只更新登录时间，不走乐观锁，避免与管理员同时修改用户资料时互相冲突 */
    @Update("UPDATE sys_user SET last_login_time = #{time} WHERE id = #{userId}")
    int updateLastLoginTime(@Param("userId") Long userId, @Param("time") LocalDateTime time);

    @Update("UPDATE sys_user SET password_hash = #{hash}, password_changed_at = #{time}, must_change_password = #{mustChange} "
            + "WHERE id = #{userId}")
    int updatePassword(@Param("userId") Long userId, @Param("hash") String hash, @Param("time") LocalDateTime time,
                       @Param("mustChange") int mustChange);

    @Select("SELECT role_id FROM sys_user_role WHERE user_id = #{userId}")
    List<Long> selectRoleIds(@Param("userId") Long userId);

    @Delete("DELETE FROM sys_user_role WHERE user_id = #{userId}")
    int deleteUserRoles(@Param("userId") Long userId);

    @Select("SELECT id, role_code, role_name FROM sys_role WHERE status = 1 ORDER BY id")
    List<Map<String, Object>> selectRoles();
}
