package io.github.ryancamus.clinic.event.service;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import io.github.ryancamus.clinic.event.api.OutboxHandler;
import io.github.ryancamus.clinic.event.api.OutboxPublisher;
import io.github.ryancamus.clinic.event.domain.entity.OutboxMessage;
import io.github.ryancamus.clinic.event.mapper.OutboxMessageMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 发件箱的写入与投递。
 * <p>
 * 投递：一个事务里用 {@code FOR UPDATE SKIP LOCKED} 锁住一批到期事件，逐条交给消费者；
 * 每个消费者在独立的新事务里先插入消费记录（INSERT IGNORE），插入成功才执行业务，
 * 于是“至少一次投递 + 幂等消费 = 恰好一次效果”。消费失败按 1s、5s、30s、2min… 退避，10 次后转死信。
 */
@Slf4j
@Service
public class OutboxService implements OutboxPublisher {

    private static final Duration[] BACKOFF = {Duration.ofSeconds(1), Duration.ofSeconds(5), Duration.ofSeconds(30),
            Duration.ofMinutes(2), Duration.ofMinutes(10), Duration.ofMinutes(30), Duration.ofHours(1),
            Duration.ofHours(2), Duration.ofHours(4), Duration.ofHours(8)};

    private final OutboxMessageMapper mapper;
    private final ObjectMapper objectMapper;
    private final ObjectProvider<OutboxHandler> handlers;
    private final Clock clock;
    private final TransactionTemplate pollTx;
    private final TransactionTemplate consumeTx;

    public OutboxService(OutboxMessageMapper mapper, ObjectMapper objectMapper, ObjectProvider<OutboxHandler> handlers,
                         Clock clock, PlatformTransactionManager txManager) {
        this.mapper = mapper;
        this.objectMapper = objectMapper;
        this.handlers = handlers;
        this.clock = clock;
        this.pollTx = new TransactionTemplate(txManager);
        this.consumeTx = new TransactionTemplate(txManager);
        this.consumeTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Override
    public void publish(String eventType, Long aggregateId, Object payload) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            // 发件箱的意义在于与业务同事务；没有事务时说明调用方用错了
            throw new IllegalStateException("OutboxPublisher.publish 必须在业务事务内调用");
        }
        OutboxMessage m = new OutboxMessage();
        m.setId(IdWorker.getId());
        m.setEventType(eventType);
        m.setAggregateId(aggregateId);
        try {
            m.setPayload(objectMapper.writeValueAsString(payload));
        } catch (JacksonException e) {
            throw new IllegalArgumentException("事件内容无法序列化", e);
        }
        m.setStatus(OutboxMessage.PENDING);
        m.setRetryCount(0);
        LocalDateTime now = LocalDateTime.now(clock);
        m.setNextRetryTime(now);
        m.setCreateTime(now);
        mapper.insert(m);
    }

    /**
     * 投递一批到期事件。
     *
     * @param asOf 视为“当前时间”，到期判断用；测试中传入未来时间可跳过退避等待
     * @return 本批处理的事件数
     */
    public int dispatchOnce(LocalDateTime asOf, int limit) {
        Map<String, List<OutboxHandler>> byType = handlers.orderedStream()
                .collect(Collectors.groupingBy(OutboxHandler::eventType));
        Integer processed = pollTx.execute(tx -> {
            // 每个聚合一批最多取一条（见 lockDue），同一张收费单的事件严格按序消费
            List<OutboxMessage> due = mapper.lockDue(asOf, limit);
            for (OutboxMessage m : due) {
                deliver(m, byType.getOrDefault(m.getEventType(), List.of()), asOf);
            }
            return due.size();
        });
        return processed == null ? 0 : processed;
    }

    /** @return 是否投递成功 */
    private boolean deliver(OutboxMessage m, List<OutboxHandler> consumers, LocalDateTime asOf) {
        JsonNode payload;
        try {
            payload = objectMapper.readTree(m.getPayload());
        } catch (JacksonException e) {
            mapper.markFailed(m.getId(), OutboxMessage.DEAD, asOf, "payload 无法解析");
            return false;
        }
        try {
            for (OutboxHandler h : consumers) {
                consumeTx.executeWithoutResult(tx -> {
                    if (mapper.insertConsumeRecord(m.getId(), h.consumer()) == 1) {
                        h.handle(m.getId(), m.getAggregateId(), payload);
                    }
                });
            }
            mapper.markDelivered(m.getId());
            return true;
        } catch (RuntimeException e) {
            int attempt = m.getRetryCount();
            boolean dead = attempt + 1 >= BACKOFF.length;
            String error = String.valueOf(e.getMessage());
            mapper.markFailed(m.getId(), dead ? OutboxMessage.DEAD : OutboxMessage.PENDING,
                    asOf.plus(BACKOFF[Math.min(attempt, BACKOFF.length - 1)]),
                    error.length() > 500 ? error.substring(0, 500) : error);
            if (dead) {
                log.error("发件箱事件转死信 id={} type={} error={}", m.getId(), m.getEventType(), error);
            } else {
                log.warn("发件箱事件处理失败，稍后重试 id={} type={} attempt={} error={}", m.getId(), m.getEventType(),
                        attempt + 1, error);
            }
            return false;
        }
    }

    public long pendingCount() {
        return mapper.countByStatus(OutboxMessage.PENDING);
    }
}
