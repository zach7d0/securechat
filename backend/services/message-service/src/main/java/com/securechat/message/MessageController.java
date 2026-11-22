package com.securechat.message;

import com.securechat.contracts.events.Envelope;
import com.securechat.support.security.AuthenticatedUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** REST fallback for sending (same semantics as the WebSocket path) and paginated history. */
@RestController
@RequestMapping("/api/v1/conversations/{conversationId}/messages")
class MessageController {
    private final MessageService messages;

    MessageController(MessageService messages) {
        this.messages = messages;
    }

    record SendBody(@NotNull UUID clientMessageId, @NotBlank String protocol, String messageType,
            @Size(max = 64) List<Envelope> envelopes, String ciphertext, Long epoch, Integer expiresInSeconds) {
    }

    @PostMapping
    MessageService.Accepted send(@PathVariable UUID conversationId, @Valid @RequestBody SendBody b) {
        AuthenticatedUser me = AuthenticatedUser.current();
        return messages.submit(me.userId(), me.requireDevice(), new MessageService.SubmitRequest(conversationId, b.clientMessageId(),
                b.protocol(), b.messageType(), b.envelopes(), b.ciphertext(), b.epoch(), b.expiresInSeconds()));
    }

    /** Delete for everyone (original sender, within 24 h). {@code at} = the message's server timestamp. */
    @org.springframework.web.bind.annotation.DeleteMapping("/{messageId}")
    org.springframework.http.ResponseEntity<Void> delete(@PathVariable UUID conversationId, @PathVariable UUID messageId,
            @RequestParam java.time.Instant at) {
        messages.deleteForEveryone(AuthenticatedUser.current().userId(), conversationId, messageId, at);
        return org.springframework.http.ResponseEntity.noContent().build();
    }

    @GetMapping
    MessageService.Page history(@PathVariable UUID conversationId, @RequestParam(required = false) String before,
            @RequestParam(defaultValue = "50") int limit) {
        AuthenticatedUser me = AuthenticatedUser.current();
        return messages.history(me.userId(), me.requireDevice(), conversationId, before, limit);
    }
}

/** Ingress from the WebSocket Gateway, which authenticated the socket and asserts the sender. */
@RestController
@RequestMapping("/internal/v1")
class InternalMessageController {
    private final MessageService messages;
    private final CassandraOutboxPublisher outbox;

    InternalMessageController(MessageService messages, CassandraOutboxPublisher outbox) {
        this.messages = messages;
        this.outbox = outbox;
    }

    record InternalSend(@NotNull UUID senderUserId, @NotNull UUID senderDeviceId, @NotNull UUID conversationId,
            @NotNull UUID clientMessageId, @NotBlank String protocol, String messageType, @Size(max = 64) List<Envelope> envelopes,
            String ciphertext, Long epoch, Integer expiresInSeconds) {
    }

    @PostMapping("/messages")
    MessageService.Accepted submit(@Valid @RequestBody InternalSend b) {
        return messages.submit(b.senderUserId(), b.senderDeviceId(), new MessageService.SubmitRequest(b.conversationId(),
                b.clientMessageId(), b.protocol(), b.messageType(), b.envelopes(), b.ciphertext(), b.epoch(), b.expiresInSeconds()));
    }

    @GetMapping("/outbox/status")
    List<CassandraOutboxPublisher.ShardStatus> status() {
        return outbox.status();
    }

    @PostMapping("/outbox/requeue-quarantined")
    Map<String, Integer> requeue() {
        return Map.of("requeued", outbox.requeueQuarantined());
    }
}
