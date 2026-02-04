-- Presence & watch events (Android online status)

CREATE TABLE IF NOT EXISTS user_presence (
  user_id INTEGER PRIMARY KEY,
  status TEXT NOT NULL,
  channel_title TEXT,
  channel_url TEXT,
  last_seen_at TEXT NOT NULL,
  updated_at TEXT NOT NULL,
  FOREIGN KEY(user_id) REFERENCES users(id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS watch_events (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  user_id INTEGER NOT NULL,
  event TEXT NOT NULL,
  channel_title TEXT,
  channel_url TEXT,
  created_at TEXT NOT NULL,
  FOREIGN KEY(user_id) REFERENCES users(id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_user_presence_last_seen_at ON user_presence(last_seen_at);
CREATE INDEX IF NOT EXISTS idx_watch_events_user_id_created_at ON watch_events(user_id, created_at DESC);
