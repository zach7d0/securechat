package com.securechat.support.kafka;

import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;
import org.springframework.util.backoff.ExponentialBackOff;

/**
 * Consumer failure policy shared by all services:
 * <ul>
 *   <li>{@link RetryableDependencyException}: retried forever (1 s → 30 s backoff); offsets are not
 *       committed, so nothing is skipped while a dependency is down;</li>
 *   <li>other exceptions: 6 retries, then the record is copied to {@code chat.deadletter} with
 *       its original topic/offset headers (monitored; replayable) and an error is logged.</li>
 * </ul>
 */
@AutoConfiguration(afterName = "org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration")
@ConditionalOnClass(KafkaTemplate.class)
public class KafkaSupportConfiguration {
    public static final String DEAD_LETTER_TOPIC = "chat.deadletter";
    private static final Logger log = LoggerFactory.getLogger(KafkaSupportConfiguration.class);

    @Bean
    @ConditionalOnBean(KafkaTemplate.class)
    @ConditionalOnMissingBean
    EventPublisher eventPublisher(KafkaTemplate<String, String> kafka,
            @Value("${spring.application.name}") String app,
            @Value("${securechat.kafka.publish-timeout:10s}") Duration timeout) {
        return new EventPublisher(kafka, app, timeout);
    }

    @Bean
    @ConditionalOnBean(KafkaTemplate.class)
    @ConditionalOnMissingBean
    DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<String, String> kafka, MeterRegistry meters) {
        DeadLetterPublishingRecoverer dlt = new DeadLetterPublishingRecoverer(kafka,
                (rec, ex) -> new TopicPartition(DEAD_LETTER_TOPIC, -1)) {
        };
        DefaultErrorHandler h = new DefaultErrorHandler((rec, ex) -> {
            log.error("dead-lettering record topic={} partition={} offset={} cause={}", rec.topic(), rec.partition(),
                    rec.offset(), rootMessage(ex));
            meters.counter("kafka.consumer.dead_lettered", "topic", rec.topic()).increment();
            dlt.accept(rec, ex);
        }, new ExponentialBackOffWithMaxRetries(6));
        h.setBackOffFunction((rec, ex) -> {
            if (causedBy(ex, RetryableDependencyException.class)) {
                ExponentialBackOff b = new ExponentialBackOff(1000, 2.0);
                b.setMaxInterval(30_000);
                return b;
            }
            return null;
        });
        h.addNotRetryableExceptions(tools.jackson.core.JacksonException.class, IllegalArgumentException.class);
        return h;
    }

    static boolean causedBy(Throwable t, Class<? extends Throwable> type) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (type.isInstance(c)) {
                return true;
            }
        }
        return false;
    }

    static String rootMessage(Throwable t) {
        Throwable c = t;
        while (c.getCause() != null) {
            c = c.getCause();
        }
        return c.getClass().getSimpleName();
    }
}
