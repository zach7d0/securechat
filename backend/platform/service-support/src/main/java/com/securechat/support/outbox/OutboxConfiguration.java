package com.securechat.support.outbox;

import com.securechat.support.kafka.EventPublisher;
import com.securechat.support.kafka.KafkaSupportConfiguration;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Enabled by {@code securechat.outbox.enabled=true} in services that own a PostgreSQL outbox. */
@AutoConfiguration(after = KafkaSupportConfiguration.class, afterName = {
        "org.springframework.boot.jdbc.autoconfigure.JdbcTemplateAutoConfiguration",
        "org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration"})
@ConditionalOnClass(JdbcTemplate.class)
@ConditionalOnProperty(prefix = "securechat.outbox", name = "enabled", havingValue = "true")
@EnableScheduling
public class OutboxConfiguration {

    @Bean
    OutboxWriter outboxWriter(JdbcTemplate jdbc, @Value("${spring.application.name}") String app) {
        return new OutboxWriter(jdbc, app);
    }

    @Bean
    ProcessedEvents processedEvents(JdbcTemplate jdbc) {
        return new ProcessedEvents(jdbc);
    }

    @Bean
    @ConditionalOnBean(EventPublisher.class)
    @ConditionalOnProperty(prefix = "securechat.outbox", name = "publisher-enabled", havingValue = "true", matchIfMissing = true)
    OutboxPublisher outboxPublisher(JdbcTemplate jdbc, PlatformTransactionManager tm, EventPublisher publisher,
            MeterRegistry meters, @Value("${securechat.outbox.batch-size:100}") int batchSize) {
        return new OutboxPublisher(jdbc, new TransactionTemplate(tm), publisher, meters, batchSize);
    }
}
