package io.github.ryancamus.clinic.framework.mybatis;

import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import io.github.ryancamus.clinic.framework.security.SecurityUtils;
import lombok.RequiredArgsConstructor;
import org.apache.ibatis.reflection.MetaObject;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDateTime;

/**
 * 自动填充审计字段。操作人取自当前登录用户；定时任务等无登录上下文的场景记为 0（系统）。
 */
@Component
@RequiredArgsConstructor
public class AuditMetaObjectHandler implements MetaObjectHandler {

    private static final long SYSTEM_USER = 0L;

    private final Clock clock;

    @Override
    public void insertFill(MetaObject metaObject) {
        LocalDateTime now = LocalDateTime.now(clock);
        Long operator = operator();
        strictInsertFill(metaObject, "createTime", LocalDateTime.class, now);
        strictInsertFill(metaObject, "updateTime", LocalDateTime.class, now);
        strictInsertFill(metaObject, "createBy", Long.class, operator);
        strictInsertFill(metaObject, "updateBy", Long.class, operator);
        strictInsertFill(metaObject, "version", Integer.class, 0);
    }

    @Override
    public void updateFill(MetaObject metaObject) {
        // 更新时总是覆盖，避免沿用查询出来的旧值
        setFieldValByName("updateTime", LocalDateTime.now(clock), metaObject);
        setFieldValByName("updateBy", operator(), metaObject);
    }

    private static Long operator() {
        Long userId = SecurityUtils.currentUserIdOrNull();
        return userId == null ? SYSTEM_USER : userId;
    }
}
