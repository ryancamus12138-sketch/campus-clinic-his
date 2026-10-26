package io.github.ryancamus.clinic.finance.service;

import io.github.ryancamus.clinic.event.api.EventTypes;
import io.github.ryancamus.clinic.event.api.OutboxHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.JsonNode;

/**
 * 电子票据订阅的发件箱事件。
 */
@Configuration(proxyBeanMethods = false)
public class InvoiceHandlers {

    private static final String CONSUMER = "e-invoice";

    @Bean
    OutboxHandler invoiceOnPaid(InvoiceService invoiceService) {
        return handler(EventTypes.CHARGE_PAID, (id, aggregateId, payload) -> invoiceService.onPaid(payload));
    }

    @Bean
    OutboxHandler invoiceOnRefunded(InvoiceService invoiceService) {
        return handler(EventTypes.CHARGE_REFUNDED, (id, aggregateId, payload) -> invoiceService.onRefunded(payload));
    }

    private interface Body {
        void handle(Long eventId, Long aggregateId, JsonNode payload);
    }

    private static OutboxHandler handler(String type, Body body) {
        return new OutboxHandler() {
            @Override
            public String eventType() {
                return type;
            }

            @Override
            public String consumer() {
                return CONSUMER;
            }

            @Override
            public void handle(Long eventId, Long aggregateId, JsonNode payload) {
                body.handle(eventId, aggregateId, payload);
            }
        };
    }
}
