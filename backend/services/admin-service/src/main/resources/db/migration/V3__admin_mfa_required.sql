-- Per-admin MFA requirement chosen at creation. NULL = follow the global securechat.admin.mfa-required
-- setting (keeps the behaviour of admins that existed before this migration unchanged).
ALTER TABLE admin_users ADD COLUMN mfa_required BOOLEAN;
