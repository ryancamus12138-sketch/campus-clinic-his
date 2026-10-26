package io.github.ryancamus.clinic.event.api;

/**
 * 事务性发件箱。业务在自己的事务里调用 publish，事件与业务数据一起提交或一起回滚；
 * 提交后由投递器异步交给各个 {@link OutboxHandler}，失败按退避重试，不会因为进程重启而丢失。
 */
public interface OutboxPublisher {

    /**
     * @param payload 事件内容，序列化为 JSON 保存
     */
    void publish(String eventType, Long aggregateId, Object payload);
}
