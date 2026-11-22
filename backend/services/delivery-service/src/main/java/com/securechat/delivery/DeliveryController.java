package com.securechat.delivery;

import com.securechat.support.security.AuthenticatedUser;
import com.securechat.support.web.ApiException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
class DeliveryController {
    private final DeliveryService delivery;

    DeliveryController(DeliveryService delivery) {
        this.delivery = delivery;
    }

    record Acks(@NotNull UUID userId, @NotNull UUID deviceId, @NotNull String status, @NotNull @Size(max = 500) List<UUID> messageIds) {
    }

    record Replay(UUID userId, @NotNull UUID deviceId) {
    }

    record ReceiptView(UUID recipientUserId, UUID recipientDeviceId, String status, java.time.Instant deliveredAt, java.time.Instant readAt) {
    }

    /** Device ACKs relayed by the WebSocket Gateway for its authenticated device. */
    @PostMapping("/internal/v1/delivery/acks")
    ResponseEntity<Void> acks(@Valid @RequestBody Acks a) {
        if (!List.of("DELIVERED", "READ").contains(a.status())) {
            throw ApiException.badRequest("status must be DELIVERED or READ");
        }
        delivery.acknowledge(a.userId(), a.deviceId(), a.status(), a.messageIds());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/internal/v1/delivery/replay")
    Map<String, Integer> replay(@Valid @RequestBody Replay r) {
        return Map.of("replayed", delivery.replay(r.deviceId()));
    }

    /** Authoritative per-device state (Admin trace / monitoring reconciliation). */
    @GetMapping("/internal/v1/delivery/messages/{messageId}/receipts")
    List<DeliveryStore.Receipt> internalReceipts(@PathVariable UUID messageId) {
        return delivery.receipts(messageId);
    }

    @GetMapping("/internal/v1/delivery/stats")
    Map<String, Long> stats() {
        return Map.of("pendingMessages", delivery.pendingCount());
    }

    /** The sender's clients resynchronise ticks after reconnecting. Only the sender may ask. */
    @GetMapping("/api/v1/messages/{messageId}/receipts")
    List<ReceiptView> receipts(@PathVariable UUID messageId) {
        UUID me = AuthenticatedUser.current().userId();
        List<DeliveryStore.Receipt> rs = delivery.receipts(messageId);
        if (rs.isEmpty() || !rs.get(0).senderUserId().equals(me)) {
            throw ApiException.notFound("message");
        }
        return rs.stream().map(r -> new ReceiptView(r.recipientUserId(), r.deviceId(), r.status(), r.deliveredAt(), r.readAt())).toList();
    }
}
