-- Manual approval required. Run duplicate audit first and resolve conflicts by hand.
-- SELECT lower(btrim(user_email)), count(*) FROM users GROUP BY 1 HAVING count(*) > 1;
BEGIN;
LOCK TABLE users IN SHARE ROW EXCLUSIVE MODE;
-- Unique index fails safely, rolling back this migration if collisions exist.
CREATE UNIQUE INDEX users_normalized_email_unique ON users (lower(btrim(user_email)));
UPDATE users SET user_email = lower(btrim(user_email))
WHERE user_email IS DISTINCT FROM lower(btrim(user_email));
ALTER TABLE users ADD CONSTRAINT users_email_normalized CHECK (user_email = lower(btrim(user_email)));
-- Existing accounts remain intact but must verify ownership through resend before login.
ALTER TABLE users ADD COLUMN email_verified boolean NOT NULL DEFAULT false;
ALTER TABLE users ADD COLUMN session_version bigint NOT NULL DEFAULT 0;
CREATE TABLE account_tokens (
    hash varchar(64) PRIMARY KEY,
    user_id bigint NOT NULL REFERENCES users(user_id) ON DELETE CASCADE,
    purpose varchar(255) NOT NULL CHECK (purpose IN ('verify', 'reset')),
    expires_at timestamptz NOT NULL,
    UNIQUE(user_id, purpose)
);
-- Tokens must only be accessed by the backend database role, never the public client.
ALTER TABLE account_tokens ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON account_tokens FROM anon, authenticated;
COMMIT;
