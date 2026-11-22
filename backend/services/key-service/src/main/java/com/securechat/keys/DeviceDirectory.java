package com.securechat.keys;

import com.securechat.support.client.InternalClients;
import com.securechat.support.security.SecureChatSecurityProperties;
import com.securechat.support.web.ApiException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/** User Service lookups (device ownership/status). Every failure is fail-closed (503). */
@Component
public class DeviceDirectory {
    public record DeviceInfo(UUID id, UUID userId, String status) {
    }

    private final RestClient client;

    DeviceDirectory(RestClient.Builder builder, SecureChatSecurityProperties security,
            @Value("${securechat.internal.user-url}") String userUrl) {
        this.client = InternalClients.create(builder, userUrl, security.internalToken(), Duration.ofSeconds(3));
    }

    /** @return the device, or null if it does not exist */
    public DeviceInfo device(UUID deviceId) {
        try {
            return client.get().uri("/internal/v1/devices/{id}", deviceId).retrieve().body(DeviceInfo.class);
        } catch (HttpClientErrorException.NotFound e) {
            return null;
        } catch (RestClientException e) {
            throw ApiException.unavailable("DEPENDENCY_UNAVAILABLE", "device directory unavailable");
        }
    }

    public Map<UUID, List<UUID>> activeDevices(List<UUID> userIds) {
        try {
            return client.post().uri("/internal/v1/devices:active").body(Map.of("userIds", userIds)).retrieve()
                    .body(new ParameterizedTypeReference<Map<UUID, List<UUID>>>() { });
        } catch (RestClientException e) {
            throw ApiException.unavailable("DEPENDENCY_UNAVAILABLE", "device directory unavailable");
        }
    }
}
