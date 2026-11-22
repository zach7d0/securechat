package com.securechat.admin;

/**
 * Server-enforced permission expressions (used in @PreAuthorize on every endpoint).
 * Least privilege: e.g. Reporting admins see aggregates only; only Security/Super can act on users.
 */
public final class Roles {
    public static final String ANY = "hasAnyRole('SUPER_ADMIN','OPERATIONS_ADMIN','SUPPORT_ADMIN','SECURITY_ADMIN','REPORTING_ADMIN')";
    public static final String USERS_READ = "hasAnyRole('SUPER_ADMIN','OPERATIONS_ADMIN','SUPPORT_ADMIN','SECURITY_ADMIN')";
    public static final String USERS_ACT = "hasAnyRole('SUPER_ADMIN','SECURITY_ADMIN')";
    public static final String TRACE = "hasAnyRole('SUPER_ADMIN','OPERATIONS_ADMIN','SUPPORT_ADMIN')";
    public static final String REPORTS = "hasAnyRole('SUPER_ADMIN','OPERATIONS_ADMIN','REPORTING_ADMIN')";
    public static final String OPS = "hasAnyRole('SUPER_ADMIN','OPERATIONS_ADMIN')";
    public static final String ALERTS = "hasAnyRole('SUPER_ADMIN','OPERATIONS_ADMIN','SECURITY_ADMIN')";
    public static final String AUDIT = "hasAnyRole('SUPER_ADMIN','SECURITY_ADMIN')";
    public static final String SUPER = "hasRole('SUPER_ADMIN')";
    public static final java.util.Set<String> ALL = java.util.Set.of("SUPER_ADMIN", "OPERATIONS_ADMIN", "SUPPORT_ADMIN", "SECURITY_ADMIN",
            "REPORTING_ADMIN");

    private Roles() {
    }
}
