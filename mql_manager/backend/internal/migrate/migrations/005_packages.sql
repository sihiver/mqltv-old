PRAGMA foreign_keys = ON;

CREATE TABLE IF NOT EXISTS packages (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  name TEXT NOT NULL UNIQUE,
  created_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS package_channels (
  package_id INTEGER NOT NULL,
  channel_id INTEGER NOT NULL,
  pos INTEGER NOT NULL DEFAULT 0,
  PRIMARY KEY (package_id, channel_id),
  FOREIGN KEY (package_id) REFERENCES packages(id) ON DELETE CASCADE,
  FOREIGN KEY (channel_id) REFERENCES channels(id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_packages_name ON packages(name);
CREATE INDEX IF NOT EXISTS idx_package_channels_package_pos ON package_channels(package_id, pos);
CREATE INDEX IF NOT EXISTS idx_package_channels_channel ON package_channels(channel_id);
