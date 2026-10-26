package io.github.ryancamus.clinic.schedule.service;

import io.github.ryancamus.clinic.common.exception.BizException;
import io.github.ryancamus.clinic.common.exception.CommonErrorCode;
import io.github.ryancamus.clinic.schedule.domain.entity.Registration;
import io.github.ryancamus.clinic.schedule.enums.ScheduleErrorCode;
import io.github.ryancamus.clinic.schedule.mapper.RegistrationMapper;
import io.github.ryancamus.clinic.schedule.mapper.ScheduleSlotMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 预约合并写入。
 * <p>
 * 压测发现：放号第一秒内 300 个成功请求都要更新同一行号源，行锁 + 每次提交刷盘让它们只能串行，
 * 成功请求的 P99 达到 551ms。这里把通过 Redis 闸门的请求放进队列，由单个写线程按号源分组，
 * 一个事务批量插入预约，并用一条 {@code remaining = remaining - k WHERE remaining >= k} 扣减号源：
 * 300 次行锁和刷盘变成几次。
 * <p>
 * 正确性不依赖批处理：只要批量插入遇到唯一键冲突，或余量不足 k，整批回滚并逐条处理，
 * 逐条处理的逻辑与合并前完全相同，数据库仍是防超卖的最后一道防线。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BookingWriter {

    private static final int MAX_BATCH = 200;
    private static final int QUEUE_CAPACITY = 10_000;
    private static final long WAIT_SECONDS = 5;

    private final RegistrationMapper registrationMapper;
    private final ScheduleSlotMapper slotMapper;
    private final TransactionTemplate transactionTemplate;

    private final BlockingQueue<Command> queue = new LinkedBlockingQueue<>(QUEUE_CAPACITY);
    private volatile boolean running = true;
    private Thread worker;

    @PostConstruct
    void start() {
        worker = Thread.ofPlatform().name("booking-writer").daemon(true).start(this::loop);
    }

    @PreDestroy
    void stop() throws InterruptedException {
        running = false;
        worker.interrupt();
        worker.join(TimeUnit.SECONDS.toMillis(WAIT_SECONDS));
    }

    /**
     * 提交一条预约并等待写入结果。
     *
     * @throws BizException 号源已满（数据库判定）、重复预约、或系统繁忙
     */
    public void write(Registration registration) {
        Command command = new Command(registration, new CompletableFuture<>());
        if (!running || !queue.offer(command)) {
            throw new BizException(CommonErrorCode.TOO_MANY_REQUESTS);
        }
        try {
            command.done().get(WAIT_SECONDS, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof RuntimeException re) {
                throw re;
            }
            throw new IllegalStateException(e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BizException(CommonErrorCode.INTERNAL_ERROR);
        } catch (TimeoutException e) {
            // 写线程仍可能在之后完成写入；调用方会归还 Redis 名额，偏差由对账任务以数据库为准修正
            throw new BizException(CommonErrorCode.TOO_MANY_REQUESTS);
        }
    }

    private void loop() {
        List<Command> batch = new ArrayList<>(MAX_BATCH);
        while (running || !queue.isEmpty()) {
            try {
                Command first = queue.poll(200, TimeUnit.MILLISECONDS);
                if (first == null) {
                    continue;
                }
                batch.add(first);
                queue.drainTo(batch, MAX_BATCH - 1);
                process(batch);
            } catch (InterruptedException e) {
                if (!running) {
                    break;
                }
            } catch (Throwable t) {
                log.error("预约合并写入异常", t);
                batch.forEach(c -> c.done().completeExceptionally(new BizException(CommonErrorCode.INTERNAL_ERROR)));
            } finally {
                batch.clear();
            }
        }
    }

    private void process(List<Command> batch) {
        Map<Long, List<Command>> bySlot = new LinkedHashMap<>();
        batch.forEach(c -> bySlot.computeIfAbsent(c.registration().getSlotId(), k -> new ArrayList<>()).add(c));
        bySlot.forEach((slotId, commands) -> {
            if (commands.size() > 1 && tryBatch(slotId, commands)) {
                commands.forEach(c -> c.done().complete(null));
            } else {
                commands.forEach(this::writeOne);
            }
        });
    }

    /** 整批写入；任何一条不满足条件就整批回滚，返回 false 交给逐条处理 */
    private boolean tryBatch(Long slotId, List<Command> commands) {
        try {
            return Boolean.TRUE.equals(transactionTemplate.execute(tx -> {
                registrationMapper.insertBatch(commands.stream().map(Command::registration).toList());
                if (slotMapper.decrRemainingBy(slotId, commands.size()) == 0) {
                    tx.setRollbackOnly();
                    return false;
                }
                return true;
            }));
        } catch (DuplicateKeyException e) {
            return false;
        }
    }

    /** 逐条写入，与合并前的单条事务逻辑一致 */
    private void writeOne(Command command) {
        Registration reg = command.registration();
        try {
            transactionTemplate.executeWithoutResult(tx -> {
                registrationMapper.insertBatch(List.of(reg));
                if (slotMapper.decrRemaining(reg.getSlotId()) == 0) {
                    throw new BizException(ScheduleErrorCode.SLOT_FULL);
                }
            });
            command.done().complete(null);
        } catch (RuntimeException e) {
            command.done().completeExceptionally(e);
        }
    }

    private record Command(Registration registration, CompletableFuture<Void> done) {
    }
}
