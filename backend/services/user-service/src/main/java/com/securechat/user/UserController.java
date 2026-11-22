package com.securechat.user;

import com.securechat.support.security.AuthenticatedUser;
import com.securechat.support.web.ApiException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
class UserController {
    private final ProfileService profiles;
    private final DeviceService devices;

    UserController(ProfileService profiles, DeviceService devices) {
        this.profiles = profiles;
        this.devices = devices;
    }

    /** Public view of another user (no email, no account internals). */
    record PublicProfile(UUID userId, String username, String displayName, String avatarUrl, String bio) {
        static PublicProfile of(ProfileService.Profile p) {
            return new PublicProfile(p.userId(), p.username(), p.displayName(), p.avatarUrl(), p.bio());
        }
    }

    record UpdateProfile(@Size(max = 100) String displayName, @Size(max = 500) String bio, @Size(max = 500) String avatarUrl) {
    }

    /** Accepted device fields: identifiers and metadata only. Unknown fields (e.g. keys) → 400. */
    record RegisterDevice(UUID deviceId, @Size(max = 100) String deviceName, @Size(max = 40) String platform,
            @Size(max = 30) String deviceType) {
    }

    @GetMapping("/users/me")
    ProfileService.Profile me() {
        AuthenticatedUser me = AuthenticatedUser.current();
        return profiles.me(me.userId(), me.username());
    }

    @PatchMapping("/users/me")
    ProfileService.Profile update(@Valid @RequestBody UpdateProfile r) {
        AuthenticatedUser me = AuthenticatedUser.current();
        profiles.me(me.userId(), me.username());
        return profiles.update(me.userId(), r.displayName(), r.bio(), r.avatarUrl());
    }

    @GetMapping("/users/search")
    List<PublicProfile> search(@RequestParam String q, @RequestParam(defaultValue = "20") int limit) {
        return profiles.search(q, AuthenticatedUser.current().userId(), limit).stream().map(PublicProfile::of).toList();
    }

    @GetMapping("/users")
    List<PublicProfile> batch(@RequestParam String ids) {
        List<UUID> list;
        try {
            list = Arrays.stream(ids.split(",")).filter(s -> !s.isBlank()).map(String::trim).map(UUID::fromString).toList();
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("ids must be UUIDs");
        }
        return profiles.batch(list).stream().map(PublicProfile::of).toList();
    }

    @GetMapping("/users/{id}")
    PublicProfile user(@PathVariable UUID id) {
        return PublicProfile.of(profiles.find(id).orElseThrow(() -> ApiException.notFound("user")));
    }

    @GetMapping("/contacts")
    List<PublicProfile> contacts() {
        return profiles.contacts(AuthenticatedUser.current().userId()).stream().map(PublicProfile::of).toList();
    }

    @PutMapping("/contacts/{userId}")
    ResponseEntity<Void> addContact(@PathVariable UUID userId) {
        profiles.addContact(AuthenticatedUser.current().userId(), userId);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/contacts/{userId}")
    ResponseEntity<Void> removeContact(@PathVariable UUID userId) {
        profiles.removeContact(AuthenticatedUser.current().userId(), userId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/devices")
    ResponseEntity<DeviceService.Device> registerDevice(@Valid @RequestBody RegisterDevice r) {
        AuthenticatedUser me = AuthenticatedUser.current();
        profiles.me(me.userId(), me.username());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(devices.register(me.userId(), r.deviceId(), r.deviceName(), r.platform(), r.deviceType()));
    }

    @GetMapping("/devices")
    List<DeviceService.Device> myDevices() {
        return devices.listVisible(AuthenticatedUser.current().userId());
    }

    /** Revoke (if still active) and hide from the owner's device list. */
    @PostMapping("/devices/{id}/remove")
    ResponseEntity<Void> removeDevice(@PathVariable UUID id) {
        devices.removeOwn(AuthenticatedUser.current().userId(), id);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/devices/{id}")
    DeviceService.Device revoke(@PathVariable UUID id) {
        return devices.revokeOwn(AuthenticatedUser.current().userId(), id);
    }
}
