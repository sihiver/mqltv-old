PRAGMA foreign_keys = OFF;

-- Tambah metadata JSON Vision+ dan izinkan banyak channel dengan stream_url sama
-- (event berbeda, URL identik) — dibedakan lewat source_id.

CREATE TABLE channels_new (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  name TEXT NOT NULL,
  stream_url TEXT NOT NULL,
  tvg_id TEXT NOT NULL DEFAULT '',
  tvg_name TEXT NOT NULL DEFAULT '',
  tvg_logo TEXT NOT NULL DEFAULT '',
  group_title TEXT NOT NULL DEFAULT '',
  source_id TEXT NOT NULL DEFAULT '',
  extra_json TEXT NOT NULL DEFAULT '',
  created_at TEXT NOT NULL
);

INSERT INTO channels_new(id, name, stream_url, tvg_id, tvg_name, tvg_logo, group_title, source_id, extra_json, created_at)
SELECT id, name, stream_url, tvg_id, tvg_name, tvg_logo, group_title, '', '', created_at
FROM channels;

DROP TABLE channels;
ALTER TABLE channels_new RENAME TO channels;

CREATE UNIQUE INDEX IF NOT EXISTS idx_channels_source_id ON channels(source_id) WHERE source_id != '';
CREATE INDEX IF NOT EXISTS idx_channels_stream_url ON channels(stream_url);

PRAGMA foreign_keys = ON;
