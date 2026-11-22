package com.securechat.presence;

import com.securechat.support.security.AuthenticatedUser;
import com.securechat.support.web.ApiException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
class PresenceController {
    private final PresenceService presence;

    PresenceController(PresenceService presence) {
        this.presence = presence;
    }

    @GetMapping("/api/v1/presence")
    List<PresenceService.PresenceView> query(@RequestParam String userIds) {
        List<UUID> ids;
        try {
            ids = Arrays.stream(userIds.split(",")).filter(s -> !s.isBlank()).map(String::trim).map(UUID::fromString).toList();
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("userIds must be UUIDs");
        }
        return presence.query(AuthenticatedUser.current().userId(), ids);
    }

    record DeviceEvent(UUID userId, UUID deviceId, String gatewayId) {
    }

    record Heartbeat(String gatewayId, List<Map<String, UUID>> devices) {
    }

    @PostMapping("/internal/v1/presence/connect")
    ResponseEntity<Void> connect(@RequestBody DeviceEvent e) {
        presence.connect(e.userId(), e.deviceId(), e.gatewayId());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/internal/v1/presence/disconnect")
    ResponseEntity<Void> disconnect(@RequestBody DeviceEvent e) {
        presence.disconnect(e.userId(), e.deviceId(), e.gatewayId());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/internal/v1/presence/heartbeat")
    ResponseEntity<Void> heartbeat(@RequestBody Heartbeat h) {
        presence.heartbeat(h.gatewayId(), h.devices() == null ? List.of() : h.devices());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/internal/v1/presence/stats")
    Map<String, Object> stats() {
        return presence.stats();
    }
}
