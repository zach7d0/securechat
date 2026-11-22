package com.securechat.conversation;

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
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/** User Service lookups; failures are fail-closed (503). */
@Component
public class UserDirectory {
    private final RestClient client;

    UserDirectory(RestClient.Builder builder, SecureChatSecurityProperties security,
            @Value("${securechat.internal.user-url}") String userUrl) {
        this.client = InternalClients.create(builder, userUrl, security.internalToken(), Duration.ofSeconds(3));
    }

    public List<UUID> existing(List<UUID> userIds) {
        try {
            return client.post().uri("/internal/v1/users:exist").body(Map.of("userIds", userIds)).retrieve()
                    .body(new ParameterizedTypeReference<List<UUID>>() { });
        } catch (RestClientException e) {
            throw ApiException.unavailable("DEPENDENCY_UNAVAILABLE", "user directory unavailable");
        }
    }

    public Map<UUID, List<UUID>> activeDevices(List<UUID> userIds) {
        try {
            return client.post().uri("/internal/v1/devices:active").body(Map.of("userIds", userIds)).retrieve()
                    .body(new ParameterizedTypeReference<Map<UUID, List<UUID>>>() { });
        } catch (RestClientException e) {
            throw ApiException.unavailable("DEPENDENCY_UNAVAILABLE", "user directory unavailable");
        }
    }
}
