package io.github.ryancamus.clinic.pharmacy.trace;

import java.util.List;

/**
 * 追溯码上传到医保平台。真实接口需要定点医疗机构资质；正式代码中目前没有实现（未接入），
 * 此时追溯码只在本地采集保存，状态为“待上传”。测试中使用测试替身。
 */
public interface TraceCodeUploader {

    /** @return 是否上传成功 */
    boolean upload(List<String> traceCodes);
}
