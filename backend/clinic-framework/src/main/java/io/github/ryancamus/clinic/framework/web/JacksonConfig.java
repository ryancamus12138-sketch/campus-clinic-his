package io.github.ryancamus.clinic.framework.web;

import io.github.ryancamus.clinic.common.money.Money;
import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.core.JsonGenerator;
import tools.jackson.core.JsonParser;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.ValueSerializer;
import tools.jackson.databind.module.SimpleModule;
import tools.jackson.databind.ser.std.ToStringSerializer;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * JSON 序列化约定：
 * <ul>
 *     <li>主键使用 64 位雪花 ID，超过 JavaScript 安全整数范围（2^53），Long 包装类型输出为字符串；
 *     分页总数等 long 基本类型仍输出为数字。</li>
 *     <li>金额 {@link Money} 输出为两位小数的字符串（如 "12.30"），前端不做浮点运算。</li>
 *     <li>时间 {@link LocalDateTime} 输出为 "2026-10-26 15:05:14"，页面直接显示；读入时两种写法（中间是空格或 T）都接受。</li>
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
public class JacksonConfig {

    @Bean
    public JsonMapperBuilderCustomizer clinicJacksonCustomizer() {
        SimpleModule module = new SimpleModule("clinic")
                .addSerializer(Long.class, ToStringSerializer.instance)
                .addSerializer(Money.class, new MoneySerializer())
                .addDeserializer(Money.class, new MoneyDeserializer())
                .addSerializer(LocalDateTime.class, new DateTimeSerializer())
                .addDeserializer(LocalDateTime.class, new DateTimeDeserializer());
        return builder -> builder.addModule(module);
    }

    static class MoneySerializer extends ValueSerializer<Money> {
        @Override
        public void serialize(Money value, JsonGenerator gen, SerializationContext ctxt) {
            gen.writeString(value.amount().toPlainString());
        }
    }

    static class MoneyDeserializer extends ValueDeserializer<Money> {
        @Override
        public Money deserialize(JsonParser p, DeserializationContext ctxt) {
            return new Money(new BigDecimal(p.getValueAsString()));
        }
    }

    static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    static class DateTimeSerializer extends ValueSerializer<LocalDateTime> {
        @Override
        public void serialize(LocalDateTime value, JsonGenerator gen, SerializationContext ctxt) {
            gen.writeString(DATE_TIME.format(value));
        }
    }

    static class DateTimeDeserializer extends ValueDeserializer<LocalDateTime> {
        @Override
        public LocalDateTime deserialize(JsonParser p, DeserializationContext ctxt) {
            return LocalDateTime.parse(p.getValueAsString().trim().replace(' ', 'T'));
        }
    }
}
