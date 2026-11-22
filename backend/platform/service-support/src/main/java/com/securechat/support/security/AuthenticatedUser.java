package com.securechat.support.security;

import com.securechat.support.web.ApiException;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * Caller identity from a validated access token. {@code deviceId} is present only for
 * device-bound tokens (claim {@code did}); device-scoped operations must use {@link #requireDevice()}.
 */
public record AuthenticatedUser(UUID userId, UUID deviceId, String username, UUID sessionId, Instant expiresAt) {

    public static AuthenticatedUser current() {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        if (a instanceof JwtAuthenticationToken t) {
            return from(t.getToken());
        }
        throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "authentication required");
    }

    public static AuthenticatedUser from(Jwt jwt) {
        String did = jwt.getClaimAsString("did");
        String sid = jwt.getClaimAsString("sid");
        return new AuthenticatedUser(UUID.fromString(jwt.getSubject()), did == null ? null : UUID.fromString(did),
                jwt.getClaimAsString("preferred_username"), sid == null ? null : UUID.fromString(sid), jwt.getExpiresAt());
    }

    public UUID requireDevice() {
        if (deviceId == null) {
            throw new ApiException(HttpStatus.FORBIDDEN, "DEVICE_REQUIRED", "a device-bound session is required");
        }
        return deviceId;
    }
}
