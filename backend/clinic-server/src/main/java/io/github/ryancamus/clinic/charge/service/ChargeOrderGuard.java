package io.github.ryancamus.clinic.charge.service;

import io.github.ryancamus.clinic.charge.domain.entity.ChargeOrder;
import io.github.ryancamus.clinic.charge.domain.entity.CounterCheckout;
import io.github.ryancamus.clinic.charge.enums.ChargeErrorCode;
import io.github.ryancamus.clinic.charge.mapper.ChargeOrderMapper;
import io.github.ryancamus.clinic.charge.mapper.CounterCheckoutMapper;
import io.github.ryancamus.clinic.common.exception.BizException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.function.Function;

/**
 * 收费单级别的互斥：窗口开始结算、作废 / 超时关单，在判断“能不能做”之前都先锁住收费单这一行
 * （{@code SELECT ... FOR UPDATE}），判断和写入在同一个短事务里完成。
 * <p>
 * 这样对同一张收费单只能排队执行：不会两个窗口同时查到“没人在收”然后各收一次，也不会一边作废、一边收钱。
 * 锁只在写入占位记录（结算记录、关单）的几毫秒内持有，调用外部平台都在锁外进行。
 * 数据库唯一约束（同一单只有一条进行中的结算）仍保留，作为第二道防线。
 */
@Component
public class ChargeOrderGuard {

    private final ChargeOrderMapper orderMapper;
    private final CounterCheckoutMapper checkoutMapper;
    private final TransactionTemplate tx;

    public ChargeOrderGuard(ChargeOrderMapper orderMapper, CounterCheckoutMapper checkoutMapper,
                            PlatformTransactionManager txManager) {
        this.orderMapper = orderMapper;
        this.checkoutMapper = checkoutMapper;
        // 读已提交：拿到行锁后的查询要看到其他事务刚提交的结算记录
        this.tx = new TransactionTemplate(txManager);
        this.tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    /**
     * 锁住收费单后执行 {@code action}，传入的是加锁后重新读取的最新收费单。
     */
    public <T> T withLock(Long chargeOrderId, Function<ChargeOrder, T> action) {
        return tx.execute(status -> {
            BizException.throwIf(orderMapper.lockById(chargeOrderId) == null, ChargeErrorCode.ORDER_NOT_FOUND);
            return action.apply(orderMapper.selectById(chargeOrderId));
        });
    }

    /** 这张收费单上阻止新收费、作废的窗口结算：正在结算，或待财务处理的收款异常 */
    public CounterCheckout blockingCheckout(Long chargeOrderId) {
        return checkoutMapper.selectBlocking(chargeOrderId);
    }

    /** 在锁内调用：有进行中或待处理的窗口结算时拒绝 */
    public void requireNoBlockingCheckout(Long chargeOrderId) {
        CounterCheckout c = blockingCheckout(chargeOrderId);
        if (c == null) {
            return;
        }
        throw c.getStatus() == CounterCheckout.NEEDS_ATTENTION
                ? new BizException(ChargeErrorCode.CHECKOUT_NEEDS_ATTENTION)
                : new BizException(ChargeErrorCode.CHECKOUT_IN_PROGRESS);
    }
}
