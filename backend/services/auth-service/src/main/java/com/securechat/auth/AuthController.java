package com.securechat.auth;

import com.securechat.support.security.AuthenticatedUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
class AuthController {
    private final AuthService auth;
    private final SigningKeys keys;

    AuthController(AuthService auth, SigningKeys keys) {
        this.auth = auth;
        this.keys = keys;
    }

    record RegisterRequest(@NotBlank @Size(max = 320) String email, @NotBlank @Size(max = 64) String username,
            @NotBlank @Size(max = 128) String password, @Size(max = 100) String displayName) {
    }

    record LoginRequest(@NotBlank @Size(max = 320) String login, @NotBlank @Size(max = 128) String password) {
    }

    record RefreshRequest(@NotBlank @Size(max = 100) String refreshToken) {
    }

    record ChangeEmailRequest(@NotBlank @Size(max = 320) String email, @NotBlank @Size(max = 128) String currentPassword) {
    }

    record ChangePasswordRequest(@NotBlank @Size(max = 128) String currentPassword, @NotBlank @Size(max = 128) String newPassword) {
    }

    record DeviceSessionRequest(@NotNull UUID deviceId) {
    }

    @PostMapping("/register")
    ResponseEntity<AuthService.Session> register(@Valid @RequestBody RegisterRequest r) {
        return ResponseEntity.status(HttpStatus.CREATED).body(auth.register(r.email(), r.username(), r.password(), r.displayName()));
    }

    @PostMapping("/login")
    AuthService.Session login(@Valid @RequestBody LoginRequest r, HttpServletRequest req) {
        return auth.login(r.login(), r.password(), clientIp(req));
    }

    @PostMapping("/refresh")
    AuthService.Session refresh(@Valid @RequestBody RefreshRequest r) {
        return auth.refresh(r.refreshToken());
    }

    @PostMapping("/logout")
    ResponseEntity<Void> logout(@Valid @RequestBody RefreshRequest r) {
        auth.logout(r.refreshToken());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/device-session")
    AuthService.Session deviceSession(@Valid @RequestBody DeviceSessionRequest r) {
        AuthenticatedUser me = AuthenticatedUser.current();
        return auth.bindDevice(me.userId(), me.sessionId(), r.deviceId());
    }

    @GetMapping("/me")
    AuthenticatedUser me() {
        return AuthenticatedUser.current();
    }

    @GetMapping("/account")
    AuthService.MyAccount account() {
        return auth.myAccount(AuthenticatedUser.current().userId());
    }

    @PutMapping("/account/email")
    AuthService.MyAccount changeEmail(@Valid @RequestBody ChangeEmailRequest r, HttpServletRequest req) {
        return auth.changeEmail(AuthenticatedUser.current().userId(), r.email(), r.currentPassword(), clientIp(req));
    }

    @PutMapping("/account/password")
    Map<String, Integer> changePassword(@Valid @RequestBody ChangePasswordRequest r, HttpServletRequest req) {
        AuthenticatedUser me = AuthenticatedUser.current();
        return Map.of("otherSessionsRevoked",
                auth.changePassword(me.userId(), me.sessionId(), r.currentPassword(), r.newPassword(), clientIp(req)));
    }

    /** The API gateway sets X-Forwarded-For from the real client; direct calls fall back to the socket. */
    static String clientIp(HttpServletRequest req) {
        String xff = req.getHeader("X-Forwarded-For");
        String ip = xff != null && !xff.isBlank() ? xff.split(",")[0].trim() : req.getRemoteAddr();
        return ip.length() > 45 ? ip.substring(0, 45) : ip;
    }
}

@RestController
class JwksController {
    private final SigningKeys keys;

    JwksController(SigningKeys keys) {
        this.keys = keys;
    }

    @GetMapping("/.well-known/jwks.json")
    Map<String, Object> jwks() {
        return keys.publicJwks().toJSONObject();
    }
}
