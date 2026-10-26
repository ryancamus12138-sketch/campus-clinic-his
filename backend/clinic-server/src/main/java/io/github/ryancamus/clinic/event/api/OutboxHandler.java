package io.github.ryancamus.clinic.event.api;

import tools.jackson.databind.JsonNode;

/**
 * 发件箱事件的消费者。投递是“至少一次”，框架用 (事件 ID, 消费者) 唯一记录保证同一消费者只成功处理一次；
 * handle 抛出异常时该消费者的事务回滚，事件稍后重试。
 */
public interface OutboxHandler {

    String eventType();

    /** 消费者名称，同一事件类型可以有多个消费者，各自独立幂等 */
    String consumer();

    void handle(Long eventId, Long aggregateId, JsonNode payload);
}
