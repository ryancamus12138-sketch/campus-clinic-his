package io.github.ryancamus.clinic.pharmacy.service;

import io.github.ryancamus.clinic.pharmacy.domain.entity.DrugTraceCode;
import io.github.ryancamus.clinic.pharmacy.mapper.DrugTraceCodeMapper;
import io.github.ryancamus.clinic.pharmacy.trace.TraceCodeUploader;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 追溯码异步上传：发药时只采集入库，上传失败不影响发药，由定时任务重试；
 * 超过重试上限的留在“上传失败”状态，供药房每日核对。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TraceUploadService {

    static final int MAX_RETRY = 10;

    private final DrugTraceCodeMapper traceMapper;
    /** 追溯码上传平台；未接入时为空，追溯码只在本地采集保存，状态保持“待上传” */
    private final ObjectProvider<TraceCodeUploader> uploader;
    private final Clock clock;

    public boolean connected() {
        return uploader.getIfAvailable() != null;
    }

    public int uploadPending(int limit) {
        TraceCodeUploader client = uploader.getIfAvailable();
        if (client == null) {
            return 0;
        }
        List<DrugTraceCode> pending = traceMapper.selectToUpload(MAX_RETRY, limit);
        if (pending.isEmpty()) {
            return 0;
        }
        boolean ok;
        try {
            ok = client.upload(pending.stream().map(DrugTraceCode::getTraceCode).toList());
        } catch (RuntimeException e) {
            log.warn("追溯码上传异常", e);
            ok = false;
        }
        LocalDateTime now = LocalDateTime.now(clock);
        for (DrugTraceCode t : pending) {
            if (ok) {
                traceMapper.markUploaded(t.getId(), now);
            } else {
                traceMapper.markUploadFailed(t.getId());
            }
        }
        return ok ? pending.size() : 0;
    }
}
