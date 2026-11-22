package com.securechat.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.securechat.contracts.events.NotificationRequested;
import com.securechat.support.kafka.RetryableDependencyException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class NotificationServiceTest {
    final List<PushProvider.Push> sent = new ArrayList<>();

    NotificationService svc(PushProvider p) {
        return new NotificationService(p, new SimpleMeterRegistry(), Duration.ofSeconds(30), Duration.ofMinutes(10));
    }

    @Test
    void pushIsGenericAndNeverCarriesContent() {
        NotificationService s = svc(sent::add);
        assertThat(s.handle(new NotificationRequested(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), Instant.now()))).isTrue();
        assertThat(sent).hasSize(1);
        assertThat(sent.get(0).title()).isEqualTo("SecureChat");
        assertThat(sent.get(0).body()).isEqualTo("You have a new message");
        assertThat(PushProvider.Push.class.getRecordComponents()).extracting(c -> c.getName())
                .containsExactly("userId", "deviceId", "title", "body");
    }

    @Test
    void duplicatesAndBurstsAreSuppressedPerDevice() {
        NotificationService s = svc(sent::add);
        UUID device = UUID.randomUUID();
        UUID mid = UUID.randomUUID();
        assertThat(s.handle(new NotificationRequested(UUID.randomUUID(), device, mid, Instant.now()))).isTrue();
        assertThat(s.handle(new NotificationRequested(UUID.randomUUID(), device, mid, Instant.now()))).isFalse();
        assertThat(s.handle(new NotificationRequested(UUID.randomUUID(), device, UUID.randomUUID(), Instant.now()))).isFalse();
        assertThat(s.handle(new NotificationRequested(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), Instant.now()))).isTrue();
        assertThat(sent).hasSize(2);
    }

    @Test
    void providerFailureIsRetryableAndNotRecordedAsSent() {
        NotificationService s = svc(p -> {
            throw new java.io.IOException("provider down");
        });
        NotificationRequested r = new NotificationRequested(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), Instant.now());
        assertThatThrownBy(() -> s.handle(r)).isInstanceOf(RetryableDependencyException.class);
        NotificationService ok = svc(sent::add);
        assertThat(ok.handle(r)).isTrue();
    }
}
