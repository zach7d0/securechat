package com.securechat.user;

import com.securechat.support.security.AuthenticatedUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Device-to-device history transfer relay. Device-bound tokens only. */
@RestController
@RequestMapping("/api/v1/devices/transfers")
class TransferController {
    private final TransferService transfers;

    TransferController(TransferService transfers) {
        this.transfers = transfers;
    }

    record RequestBodyV(@NotBlank String ephemeralPublicKey, @NotBlank String signature, java.time.Instant windowFrom,
            java.time.Instant windowTo) {
    }

    record Chunk(@NotBlank String data) {
    }

    record Complete(@NotBlank String manifest, @NotBlank String signature, int chunkCount) {
    }

    private static AuthenticatedUser me() {
        AuthenticatedUser u = AuthenticatedUser.current();
        u.requireDevice();
        return u;
    }

    @PostMapping
    ResponseEntity<Map<String, Object>> request(@Valid @RequestBody RequestBodyV r) {
        AuthenticatedUser u = me();
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(TransferService.view(transfers.request(u.userId(), u.deviceId(), r.ephemeralPublicKey(), r.signature(),
                        r.windowFrom(), r.windowTo())));
    }

    @GetMapping
    List<Map<String, Object>> list() {
        AuthenticatedUser u = me();
        return transfers.visible(u.userId(), u.deviceId()).stream().map(TransferService::view).toList();
    }

    @PostMapping("/{id}/approve")
    Map<String, Object> approve(@PathVariable UUID id) {
        AuthenticatedUser u = me();
        return TransferService.view(transfers.approve(u.userId(), u.deviceId(), id));
    }

    @PostMapping("/{id}/decline")
    ResponseEntity<Void> decline(@PathVariable UUID id) {
        AuthenticatedUser u = me();
        transfers.decline(u.userId(), u.deviceId(), id);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/{id}/chunks/{seq}")
    ResponseEntity<Void> putChunk(@PathVariable UUID id, @PathVariable int seq, @Valid @RequestBody Chunk c) {
        AuthenticatedUser u = me();
        transfers.putChunk(u.userId(), u.deviceId(), id, seq, c.data());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/complete")
    Map<String, Object> complete(@PathVariable UUID id, @Valid @RequestBody Complete c) {
        AuthenticatedUser u = me();
        return TransferService.view(transfers.complete(u.userId(), u.deviceId(), id, c.manifest(), c.signature(), c.chunkCount()));
    }

    @GetMapping("/{id}/chunks/{seq}")
    Map<String, String> getChunk(@PathVariable UUID id, @PathVariable int seq) {
        AuthenticatedUser u = me();
        return Map.of("data", transfers.getChunk(u.userId(), u.deviceId(), id, seq));
    }

    @DeleteMapping("/{id}")
    ResponseEntity<Void> finish(@PathVariable UUID id) {
        AuthenticatedUser u = me();
        transfers.finish(u.userId(), u.deviceId(), id);
        return ResponseEntity.noContent().build();
    }
}
