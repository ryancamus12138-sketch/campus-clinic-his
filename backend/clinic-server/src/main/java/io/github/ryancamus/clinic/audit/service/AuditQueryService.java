package io.github.ryancamus.clinic.audit.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.github.ryancamus.clinic.audit.domain.entity.AuditLog;
import io.github.ryancamus.clinic.audit.domain.vo.AuditLogVO;
import io.github.ryancamus.clinic.audit.mapper.AuditLogMapper;
import io.github.ryancamus.clinic.common.result.PageResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;

@Service
@RequiredArgsConstructor
public class AuditQueryService {

    private final AuditLogMapper logMapper;

    public PageResult<AuditLogVO> page(String username, String action, Boolean success, LocalDate from, LocalDate to,
                                       long page, long size) {
        Page<AuditLog> p = logMapper.selectPage(Page.of(page, size), Wrappers.<AuditLog>lambdaQuery()
                .eq(username != null && !username.isBlank(), AuditLog::getUsername, username)
                .likeRight(action != null && !action.isBlank(), AuditLog::getAction, action)
                .eq(success != null, AuditLog::getSuccess, Boolean.TRUE.equals(success) ? 1 : 0)
                .ge(from != null, AuditLog::getEventTime, from == null ? null : from.atStartOfDay())
                .lt(to != null, AuditLog::getEventTime, to == null ? null : to.plusDays(1).atStartOfDay())
                .orderByDesc(AuditLog::getId));
        List<AuditLogVO> list = p.getRecords().stream().map(l -> new AuditLogVO(l.getId(), l.getEventTime(),
                l.getUserId(), l.getUsername(), l.getClientIp(), l.getUserAgent(), l.getAction(), l.getActionName(),
                l.getHttpMethod(), l.getUri(), l.getTarget(), l.getSuccess() == 1, l.getHttpStatus(),
                l.getErrorCode(), l.getDetail(), l.getTraceId())).toList();
        return new PageResult<>(list, p.getTotal(), p.getCurrent(), p.getSize());
    }
}
