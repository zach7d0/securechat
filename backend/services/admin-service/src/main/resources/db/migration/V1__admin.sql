-- Admin Service schema (owned by admin-service / role admin_svc). No user content, no key material.
CREATE TABLE admin_users (
    id UUID PRIMARY KEY,
    username VARCHAR(64) NOT NULL UNIQUE,
    display_name VARCHAR(100) NOT NULL,
    password_hash TEXT NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'DISABLED')),
    mfa_secret_enc BYTEA,                     -- AES-256-GCM(iv || ciphertext) of the TOTP secret
    mfa_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    must_change_password BOOLEAN NOT NULL DEFAULT FALSE,
    failed_attempts INT NOT NULL DEFAULT 0,
    locked_until TIMESTAMPTZ,
    last_totp_step BIGINT,                    -- replay protection for TOTP codes
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    last_login_at TIMESTAMPTZ
);

CREATE TABLE admin_roles (
    admin_id UUID NOT NULL REFERENCES admin_users(id),
    role VARCHAR(30) NOT NULL CHECK (role IN ('SUPER_ADMIN', 'OPERATIONS_ADMIN', 'SUPPORT_ADMIN', 'SECURITY_ADMIN', 'REPORTING_ADMIN')),
    PRIMARY KEY (admin_id, role)
);

CREATE TABLE admin_audit_log (
    id BIGSERIAL PRIMARY KEY,
    at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    admin_id UUID,
    admin_username VARCHAR(64),
    action VARCHAR(60) NOT NULL,
    target_type VARCHAR(40),
    target_id VARCHAR(100),
    outcome VARCHAR(20) NOT NULL,
    details TEXT,
    client_ip VARCHAR(45),
    correlation_id VARCHAR(64)
);
CREATE INDEX idx_audit_at ON admin_audit_log (at DESC);
CREATE INDEX idx_audit_admin ON admin_audit_log (admin_id, at DESC);

CREATE TABLE incidents (
    id UUID PRIMARY KEY,
    title VARCHAR(200) NOT NULL,
    severity VARCHAR(20) NOT NULL CHECK (severity IN ('critical', 'warning', 'info')),
    status VARCHAR(20) NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN', 'ACKNOWLEDGED', 'RESOLVED')),
    source VARCHAR(100),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_by UUID NOT NULL,
    acknowledged_by UUID,
    acknowledged_at TIMESTAMPTZ,
    resolved_at TIMESTAMPTZ
);

CREATE TABLE incident_notes (
    id UUID PRIMARY KEY,
    incident_id UUID NOT NULL REFERENCES incidents(id),
    admin_id UUID NOT NULL,
    admin_username VARCHAR(64) NOT NULL,
    note TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
