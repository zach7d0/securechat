package com.securechat.user;

import com.securechat.support.web.ApiException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Service-to-service API. Requires X-Internal-Token; never routed publicly. */
@RestController
@RequestMapping("/internal/v1")
class InternalUserController {
    private final ProfileService profiles;
    private final DeviceService devices;

    InternalUserController(ProfileService profiles, DeviceService devices) {
        this.profiles = profiles;
        this.devices = devices;
    }

    record DeviceStatus(UUID id, UUID userId, String status) {
    }

    record UserIds(List<UUID> userIds) {
    }

    @GetMapping("/devices/{id}")
    DeviceStatus device(@PathVariable UUID id) {
        DeviceService.Device d = devices.find(id).orElseThrow(() -> ApiException.notFound("device"));
        return new DeviceStatus(d.id(), d.userId(), d.status());
    }

    @PostMapping("/devices:active")
    Map<UUID, List<UUID>> active(@RequestBody UserIds r) {
        return devices.activeDevices(r.userIds() == null ? List.of() : r.userIds());
    }

    @PostMapping("/devices/{id}/seen")
    ResponseEntity<Void> seen(@PathVariable UUID id, @RequestParam(defaultValue = "false") boolean authenticated) {
        devices.touch(id, authenticated);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/devices/{id}/revoke")
    DeviceService.Device adminRevoke(@PathVariable UUID id) {
        return devices.revokeByAdmin(id);
    }

    @PostMapping("/users:exist")
    List<UUID> exist(@RequestBody UserIds r) {
        return profiles.existing(r.userIds() == null ? List.of() : r.userIds());
    }

    @PostMapping("/users:batchGet")
    List<ProfileService.Profile> batch(@RequestBody UserIds r) {
        return profiles.batch(r.userIds() == null ? List.of() : r.userIds());
    }

    record AdminUser(ProfileService.Profile profile, List<DeviceService.Device> devices) {
    }

    @GetMapping("/admin/users")
    ProfileService.AdminPage list(@RequestParam(required = false) String q, @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        return profiles.adminList(q, page, size);
    }

    @GetMapping("/admin/users/{id}")
    AdminUser adminUser(@PathVariable UUID id) {
        return new AdminUser(profiles.find(id).orElseThrow(() -> ApiException.notFound("user")), devices.list(id));
    }

    @GetMapping("/stats")
    Map<String, Long> stats() {
        return Map.of("profiles", profiles.count(), "activeDevices", devices.countActive());
    }
}
