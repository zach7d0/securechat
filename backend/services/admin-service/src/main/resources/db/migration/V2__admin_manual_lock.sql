-- Manual lock set by a SUPER_ADMIN (distinct from the timed failed-login lockout in locked_until).
-- A manual lock persists until explicitly unlocked; a successful login never clears it.
ALTER TABLE admin_users ADD COLUMN locked_manually BOOLEAN NOT NULL DEFAULT FALSE;
