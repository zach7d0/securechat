package com.securechat.conversation;

import com.securechat.support.security.AuthenticatedUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
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
class ConversationController {
    private final ConversationService svc;
    private final HistoryShareService shares;

    ConversationController(ConversationService svc, HistoryShareService shares) {
        this.svc = svc;
        this.shares = shares;
    }

    record ChunkBody(@NotBlank String data) {
    }

    record CompleteShare(int chunkCount) {
    }

    record GrantBody(@NotBlank String scope) {
    }

    record DisappearingBody(int seconds) {
    }

    record DirectRequest(@NotNull UUID peerUserId) {
    }

    record GroupRequest(@NotBlank @Size(max = 150) String name, @Size(max = 1000) String description) {
    }

    record GroupUpdate(@Size(max = 150) String name, @Size(max = 1000) String description) {
    }

    record AddMembers(@NotNull @Size(min = 1, max = 100) List<UUID> userIds, @NotBlank String commit, long epoch,
            @Size(max = 2000) List<ConversationService.Welcome> welcomes, @Size(max = 20) String role) {
    }

    record CommitBody(@NotBlank String commit, long epoch, @Size(max = 2000) List<ConversationService.Welcome> welcomes, String kind) {
    }

    record RoleBody(@NotBlank String role) {
    }

    @PostMapping("/conversations/direct")
    ConversationService.Conversation direct(@Valid @RequestBody DirectRequest r) {
        return svc.direct(AuthenticatedUser.current().userId(), r.peerUserId());
    }

    @PostMapping("/groups")
    ResponseEntity<ConversationService.Conversation> group(@Valid @RequestBody GroupRequest r) {
        return ResponseEntity.status(HttpStatus.CREATED).body(svc.createGroup(AuthenticatedUser.current().userId(), r.name(), r.description()));
    }

    @GetMapping("/conversations")
    List<ConversationService.Conversation> list() {
        return svc.mine(AuthenticatedUser.current().userId());
    }

    @GetMapping("/conversations/{id}")
    ConversationService.Conversation get(@PathVariable UUID id) {
        return svc.get(id, AuthenticatedUser.current().userId());
    }

    @PatchMapping("/groups/{id}")
    ConversationService.Conversation update(@PathVariable UUID id, @Valid @RequestBody GroupUpdate r) {
        return svc.updateGroup(id, AuthenticatedUser.current().userId(), r.name(), r.description());
    }

    @PostMapping("/groups/{id}/members")
    ConversationService.Conversation add(@PathVariable UUID id, @Valid @RequestBody AddMembers r) {
        AuthenticatedUser me = AuthenticatedUser.current();
        return svc.addMembers(id, me.userId(), me.requireDevice(), r.userIds(),
                new ConversationService.CommitRequest(r.commit(), r.epoch(), r.welcomes()), r.role());
    }

    @DeleteMapping("/groups/{id}/members/{userId}")
    ConversationService.Conversation remove(@PathVariable UUID id, @PathVariable UUID userId, @Valid @RequestBody CommitBody r) {
        AuthenticatedUser me = AuthenticatedUser.current();
        return svc.removeMember(id, me.userId(), me.requireDevice(), userId, new ConversationService.CommitRequest(r.commit(), r.epoch(), null));
    }

    @PostMapping("/groups/{id}/leave")
    ResponseEntity<Void> leave(@PathVariable UUID id) {
        svc.leave(id, AuthenticatedUser.current().userId());
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/groups/{id}/members/{userId}/role")
    ConversationService.Conversation role(@PathVariable UUID id, @PathVariable UUID userId, @Valid @RequestBody RoleBody r) {
        return svc.setRole(id, AuthenticatedUser.current().userId(), userId, r.role());
    }

    @PostMapping("/groups/{id}/commits")
    Map<String, Long> commit(@PathVariable UUID id, @Valid @RequestBody CommitBody r) {
        AuthenticatedUser me = AuthenticatedUser.current();
        return Map.of("epoch", svc.commit(id, me.userId(), me.requireDevice(),
                new ConversationService.CommitRequest(r.commit(), r.epoch(), r.welcomes()), r.kind() == null ? "DEVICE_SYNC" : r.kind()));
    }

    @GetMapping("/groups/{id}/commits")
    List<ConversationService.CommitView> commits(@PathVariable UUID id, @RequestParam(defaultValue = "0") long fromEpoch,
            @RequestParam(defaultValue = "100") int limit) {
        return svc.commits(id, AuthenticatedUser.current().userId(), fromEpoch, limit);
    }

    @GetMapping("/groups/welcomes")
    List<ConversationService.WelcomeView> welcomes() {
        AuthenticatedUser me = AuthenticatedUser.current();
        return svc.welcomes(me.userId(), me.requireDevice());
    }

    // ------------------------------------------------------------------ history shared with new members (ciphertext only)

    @PostMapping("/groups/{id}/history-shares")
    ResponseEntity<Map<String, UUID>> createShare(@PathVariable UUID id) {
        AuthenticatedUser me = AuthenticatedUser.current();
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("id", shares.create(id, me.userId(), me.requireDevice())));
    }

    @PutMapping("/conversations/{id}/disappearing")
    ConversationService.Conversation disappearing(@PathVariable UUID id, @RequestBody DisappearingBody b) {
        return svc.setDisappearing(id, AuthenticatedUser.current().userId(), b.seconds());
    }

    @PostMapping("/groups/{id}/history-grants")
    ResponseEntity<Map<String, UUID>> createGrant(@PathVariable UUID id, @Valid @RequestBody GrantBody b) {
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("id", shares.grant(id, AuthenticatedUser.current().userId(), b.scope())));
    }

    @GetMapping("/groups/{id}/history-grants/{gid}")
    HistoryShareService.GrantView grant(@PathVariable UUID id, @PathVariable UUID gid) {
        return shares.grantStatus(id, AuthenticatedUser.current().userId(), gid);
    }

    @PostMapping("/groups/{id}/history-grants/{gid}/claim")
    Map<String, UUID> claimGrant(@PathVariable UUID id, @PathVariable UUID gid) {
        AuthenticatedUser me = AuthenticatedUser.current();
        return Map.of("shareId", shares.claim(id, me.userId(), me.requireDevice(), gid));
    }

    @PutMapping("/groups/{id}/history-shares/{sid}/chunks/{seq}")
    ResponseEntity<Void> putShareChunk(@PathVariable UUID id, @PathVariable UUID sid, @PathVariable int seq, @Valid @RequestBody ChunkBody b) {
        AuthenticatedUser me = AuthenticatedUser.current();
        shares.putChunk(id, me.userId(), me.requireDevice(), sid, seq, b.data());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/groups/{id}/history-shares/{sid}/complete")
    ResponseEntity<Void> completeShare(@PathVariable UUID id, @PathVariable UUID sid, @RequestBody CompleteShare b) {
        AuthenticatedUser me = AuthenticatedUser.current();
        shares.complete(id, me.userId(), me.requireDevice(), sid, b.chunkCount());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/groups/{id}/history-shares/{sid}/chunks/{seq}")
    Map<String, String> shareChunk(@PathVariable UUID id, @PathVariable UUID sid, @PathVariable int seq) {
        return Map.of("data", shares.chunk(id, AuthenticatedUser.current().userId(), sid, seq));
    }

    @DeleteMapping("/groups/welcomes/{id}")
    ResponseEntity<Void> consume(@PathVariable UUID id) {
        AuthenticatedUser me = AuthenticatedUser.current();
        svc.consumeWelcome(me.userId(), me.requireDevice(), id);
        return ResponseEntity.noContent().build();
    }
}

@RestController
@RequestMapping("/internal/v1")
class InternalConversationController {
    private final ConversationService svc;

    InternalConversationController(ConversationService svc) {
        this.svc = svc;
    }

    @GetMapping("/conversations/{id}/authorize")
    ConversationService.Authorization authorize(@PathVariable UUID id, @RequestParam UUID userId) {
        return svc.authorize(id, userId);
    }

    @GetMapping("/conversations/{id}/members")
    ConversationService.Authorization members(@PathVariable UUID id) {
        return svc.authorize(id, new UUID(0, 0));
    }

    @GetMapping("/users/{userId}/peers")
    List<UUID> peers(@PathVariable UUID userId) {
        return svc.peers(userId);
    }

    @GetMapping("/stats")
    Map<String, Long> stats() {
        return svc.stats();
    }
}
