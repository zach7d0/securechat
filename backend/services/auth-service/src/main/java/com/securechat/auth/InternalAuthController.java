package com.securechat.auth;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
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

/** Service-to-service API (Admin Service). Requires X-Internal-Token; never publicly routed. */
@RestController
@RequestMapping("/internal/v1")
class InternalAuthController {
    private final AuthService auth;

    InternalAuthController(AuthService auth) {
        this.auth = auth;
    }

    record AccountsRequest(@Size(max = 200) List<UUID> ids) {
    }

    record StatusRequest(@NotBlank String status) {
    }

    @PostMapping("/accounts:batchGet")
    List<AuthService.AccountView> accounts(@Valid @RequestBody AccountsRequest r) {
        return auth.accounts(r.ids() == null ? List.of() : r.ids());
    }

    @PutMapping("/accounts/{id}/status")
    ResponseEntity<Void> status(@PathVariable UUID id, @Valid @RequestBody StatusRequest r) {
        auth.setStatus(id, r.status());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/accounts/stats")
    Map<String, Long> stats(@RequestParam(required = false) String unused) {
        return Map.of("total", auth.countUsers(null), "active", auth.countUsers("ACTIVE"),
                "suspended", auth.countUsers("SUSPENDED"));
    }
}
