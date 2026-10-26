package io.github.ryancamus.clinic.common.result;

/**
 * 保存当前线程的链路 ID。由框架层的过滤器在请求开始时写入、结束时清除。
 * <p>
 * 放在 common 模块是为了让 {@link R} 不依赖日志框架；框架层同时把它写入 MDC。
 */
public final class TraceContext {

    private static final ThreadLocal<String> HOLDER = new ThreadLocal<>();

    private TraceContext() {
    }

    public static void set(String traceId) {
        HOLDER.set(traceId);
    }

    public static String get() {
        return HOLDER.get();
    }

    public static void clear() {
        HOLDER.remove();
    }
}
