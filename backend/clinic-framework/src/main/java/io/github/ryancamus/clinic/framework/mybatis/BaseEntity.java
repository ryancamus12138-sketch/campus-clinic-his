package io.github.ryancamus.clinic.framework.mybatis;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 实体基类，对应每张业务表都有的审计字段。
 * <ul>
 *     <li>主键：雪花 ID（BIGINT），不暴露业务量，也便于将来分库</li>
 *     <li>审计字段：由 {@link AuditMetaObjectHandler} 自动填充，业务代码不手写</li>
 *     <li>version：乐观锁，MyBatis-Plus 的 updateById 会自动带上 version 条件</li>
 * </ul>
 */
@Getter
@Setter
public abstract class BaseEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    @TableField(fill = FieldFill.INSERT)
    private Long createBy;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private Long updateBy;

    @Version
    @TableField(fill = FieldFill.INSERT)
    private Integer version;
}
