package com.securechat.keys;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/v1/keys")
class InternalKeyController {
    private final KeyService keys;

    InternalKeyController(KeyService keys) {
        this.keys = keys;
    }

    record UserIds(List<UUID> userIds) {
    }

    @PostMapping("/devices:ready")
    Map<UUID, List<UUID>> ready(@RequestBody UserIds r) {
        return keys.readyDevices(r.userIds() == null ? List.of() : r.userIds());
    }

    @GetMapping("/devices/{deviceId}/status")
    KeyService.AdminKeyStatus status(@PathVariable UUID deviceId) {
        return keys.adminStatus(deviceId);
    }
}
