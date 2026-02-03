PRAGMA foreign_keys = ON;

-- Add password hash storage for user login (Android).
ALTER TABLE users ADD COLUMN password_hash TEXT NOT NULL DEFAULT '';

CREATE INDEX IF NOT EXISTS idx_users_username ON users(username);
