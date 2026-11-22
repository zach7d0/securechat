package com.securechat.admin;

import com.securechat.support.web.Correlation;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/** Append-only audit of admin logins, sensitive searches, lookups and actions (never message content). */
@Service
public class Audit {
    private final JdbcTemplate jdbc;

    Audit(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(UUID adminId, String username, String action, String targetType, String targetId, String outcome, String details) {
        jdbc.update("INSERT INTO admin_audit_log (admin_id, admin_username, action, target_type, target_id, outcome, details, client_ip, "
                + "correlation_id) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)", adminId, username, action, targetType, targetId, outcome,
                details == null || details.length() <= 500 ? details : details.substring(0, 500), clientIp(), Correlation.current());
    }

    /** Records an action by the currently authenticated admin. */
    public void me(String action, String targetType, String targetId, String outcome, String details) {
        var a = SecurityContextHolder.getContext().getAuthentication();
        if (a instanceof JwtAuthenticationToken t) {
            record(UUID.fromString(t.getToken().getSubject()), t.getToken().getClaimAsString("preferred_username"), action, targetType,
                    targetId, outcome, details);
        }
    }

    public List<Map<String, Object>> list(int page, int size, String action) {
        int sz = Math.min(Math.max(size, 1), 200);
        if (action != null && !action.isBlank()) {
            return jdbc.queryForList("SELECT * FROM admin_audit_log WHERE action = ? ORDER BY at DESC LIMIT ? OFFSET ?", action, sz,
                    Math.max(page, 0) * sz);
        }
        return jdbc.queryForList("SELECT * FROM admin_audit_log ORDER BY at DESC LIMIT ? OFFSET ?", sz, Math.max(page, 0) * sz);
    }

    static String clientIp() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes a) {
            HttpServletRequest r = a.getRequest();
            String real = r.getHeader("X-Real-IP");
            return real != null && real.length() <= 45 ? real : r.getRemoteAddr();
        }
        return null;
    }
}
