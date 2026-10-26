package io.github.ryancamus.clinic.framework.util;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;

import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * 业务编号：前缀 + 日期 + 雪花 ID，如 R261025 + 19 位数字。
 * <p>
 * 带日期便于人工核对和按日对账；雪花 ID 保证全局唯一，不依赖数据库序列。
 * 对外只展示业务编号，不暴露主键。
 */
public final class BizNoGenerator {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyMMdd");

    private BizNoGenerator() {
    }

    public static String next(String prefix, Clock clock) {
        return prefix + LocalDate.now(clock).format(DATE) + IdWorker.getId();
    }
}
