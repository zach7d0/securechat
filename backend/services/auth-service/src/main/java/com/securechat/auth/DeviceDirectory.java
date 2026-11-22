package com.securechat.auth;

import com.securechat.support.client.InternalClients;
import com.securechat.support.kafka.RetryableDependencyException;
import com.securechat.support.security.SecureChatSecurityProperties;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/** Asks User Service (the owner of device records) about a device. Fails closed. */
@Component
public class DeviceDirectory {
    public record DeviceInfo(UUID id, UUID userId, String status) {
    }

    private final RestClient client;

    DeviceDirectory(RestClient.Builder builder, SecureChatSecurityProperties security,
            @Value("${securechat.internal.user-url}") String userUrl) {
        this.client = InternalClients.create(builder, userUrl, security.internalToken(), Duration.ofSeconds(3));
    }

    public Optional<DeviceInfo> find(UUID deviceId) {
        try {
            return Optional.ofNullable(client.get().uri("/internal/v1/devices/{id}", deviceId)
                    .retrieve()
                    .onStatus(s -> s.value() == 404, (req, res) -> { })
                    .body(DeviceInfo.class))
                    .filter(d -> d.id() != null);
        } catch (RestClientException e) {
            throw new RetryableDependencyException("user-service unavailable", e);
        }
    }

    static boolean is404(HttpStatusCode s) {
        return s.value() == 404;
    }
}
