PRAGMA foreign_keys = ON;

CREATE TABLE IF NOT EXISTS channels (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  name TEXT NOT NULL,
  stream_url TEXT NOT NULL,
  tvg_id TEXT NOT NULL DEFAULT '',
  tvg_name TEXT NOT NULL DEFAULT '',
  tvg_logo TEXT NOT NULL DEFAULT '',
  group_title TEXT NOT NULL DEFAULT '',
  created_at TEXT NOT NULL,
  UNIQUE(stream_url)
);

CREATE TABLE IF NOT EXISTS playlist_channels (
  playlist_id INTEGER NOT NULL,
  channel_id INTEGER NOT NULL,
  pos INTEGER NOT NULL DEFAULT 0,
  PRIMARY KEY (playlist_id, channel_id),
  FOREIGN KEY (playlist_id) REFERENCES playlists(id) ON DELETE CASCADE,
  FOREIGN KEY (channel_id) REFERENCES channels(id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS user_channels (
  user_id INTEGER NOT NULL,
  channel_id INTEGER NOT NULL,
  PRIMARY KEY (user_id, channel_id),
  FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
  FOREIGN KEY (channel_id) REFERENCES channels(id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_playlist_channels_playlist_pos ON playlist_channels(playlist_id, pos);
CREATE INDEX IF NOT EXISTS idx_playlist_channels_channel ON playlist_channels(channel_id);
CREATE INDEX IF NOT EXISTS idx_user_channels_user ON user_channels(user_id);
CREATE INDEX IF NOT EXISTS idx_user_channels_channel ON user_channels(channel_id);
