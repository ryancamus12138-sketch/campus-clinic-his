package io.github.ryancamus.clinic.common.result;

import java.util.List;
import java.util.function.Function;

/**
 * 分页返回体。
 *
 * @param list  当前页数据
 * @param total 总记录数
 * @param page  当前页码（从 1 开始）
 * @param size  每页条数
 */
public record PageResult<T>(List<T> list, long total, long page, long size) {

    public static <T> PageResult<T> empty(long page, long size) {
        return new PageResult<>(List.of(), 0, page, size);
    }

    public <V> PageResult<V> map(Function<T, V> converter) {
        return new PageResult<>(list.stream().map(converter).toList(), total, page, size);
    }
}
