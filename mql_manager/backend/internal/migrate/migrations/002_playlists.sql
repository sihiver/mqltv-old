PRAGMA foreign_keys = ON;

CREATE TABLE IF NOT EXISTS playlists (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  name TEXT NOT NULL,
  source_type TEXT NOT NULL, -- 'url' | 'inline'
  source_url TEXT NOT NULL DEFAULT '',
  content TEXT NOT NULL DEFAULT '',
  created_at TEXT NOT NULL
);

ALTER TABLE users ADD COLUMN app_key TEXT;
ALTER TABLE users ADD COLUMN playlist_id INTEGER;

CREATE UNIQUE INDEX IF NOT EXISTS idx_users_app_key ON users(app_key);
CREATE INDEX IF NOT EXISTS idx_users_playlist_id ON users(playlist_id);
