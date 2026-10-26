package io.github.ryancamus.clinic.pharmacy.trace;

import lombok.extern.slf4j.Slf4j;

import java.util.List;

/**
 * 药品追溯码上传接口 {@link io.github.ryancamus.clinic.pharmacy.trace.TraceCodeUploader} 的测试替身（Test Double）。
 * 只存在于 src/test，仅用于自动化测试；不是生产环境的外部平台适配器，不会进入生产构建。
 * <p>
 * 只记日志，用于验证上传任务的调度；生产环境没有实现，追溯码只保存在系统中。
 */
@Slf4j
public class MockTraceCodeUploader implements TraceCodeUploader {

    @Override
    public boolean upload(List<String> traceCodes) {
        log.debug("模拟上传追溯码 {} 个", traceCodes.size());
        return true;
    }
}
