package com.securechat.keys;

import com.securechat.support.security.AuthenticatedUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public key API. Every call needs a device-bound token whose device is ACTIVE in User Service:
 * a device can only publish its own keys, and revoked devices can neither publish nor fetch.
 * Request bodies are strict (unknown fields → 400).
 */
@RestController
@RequestMapping("/api/v1/keys")
class KeyController {
    private final KeyService keys;
    private final ClaimRateLimiter limiter;

    KeyController(KeyService keys, ClaimRateLimiter limiter) {
        this.keys = keys;
        this.limiter = limiter;
    }

    record SignedPreKeyUpload(@NotNull Long keyId, @NotNull String publicKey, @NotNull String signature) {
    }

    record PreKeysUpload(@NotNull @Size(max = 200) List<KeyService.PreKey> keys) {
    }

    record KeyPackagesUpload(@NotNull @Size(max = 50) List<KeyService.KeyPackageUpload> keyPackages) {
    }

    record ClaimRequest(Boolean includeMls, @Size(max = 32) List<UUID> deviceIds) {
    }

    private AuthenticatedUser device() {
        AuthenticatedUser me = AuthenticatedUser.current();
        keys.requireActiveDevice(me.userId(), me.requireDevice());
        return me;
    }

    @PutMapping("/identity")
    ResponseEntity<Void> identity(@Valid @RequestBody KeyService.IdentityUpload r) {
        AuthenticatedUser me = device();
        keys.putIdentity(me.userId(), me.deviceId(), r);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/signed-prekeys")
    ResponseEntity<Void> signedPreKey(@Valid @RequestBody SignedPreKeyUpload r) {
        AuthenticatedUser me = device();
        keys.putSignedPreKey(me.deviceId(), r.keyId(), r.publicKey(), r.signature());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/one-time-prekeys")
    Map<String, Integer> preKeys(@Valid @RequestBody PreKeysUpload r) {
        AuthenticatedUser me = device();
        return Map.of("stored", keys.putOneTimePreKeys(me.deviceId(), r.keys()));
    }

    @PostMapping("/mls-key-packages")
    Map<String, Integer> keyPackages(@Valid @RequestBody KeyPackagesUpload r) {
        AuthenticatedUser me = device();
        return Map.of("stored", keys.putKeyPackages(me.userId(), me.deviceId(), r.keyPackages()));
    }

    @GetMapping("/me/status")
    KeyService.KeyStatus status() {
        return keys.status(device().deviceId());
    }

    @GetMapping("/users/{userId}/identities")
    List<KeyService.DeviceIdentity> identities(@PathVariable UUID userId) {
        device();
        return keys.identities(userId);
    }

    @PostMapping("/users/{userId}/bundles:claim")
    KeyService.UserBundle claim(@PathVariable UUID userId, @RequestBody(required = false) ClaimRequest r,
            @RequestParam(required = false) Boolean includeMls) {
        AuthenticatedUser me = device();
        limiter.check(me.deviceId());
        boolean mls = Boolean.TRUE.equals(includeMls) || (r != null && Boolean.TRUE.equals(r.includeMls()));
        Set<UUID> ids = r == null || r.deviceIds() == null ? null : new HashSet<>(r.deviceIds());
        return keys.claim(me.deviceId(), userId, mls, ids);
    }
}
