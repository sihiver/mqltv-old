PRAGMA foreign_keys = ON;

ALTER TABLE playlists ADD COLUMN content_format TEXT NOT NULL DEFAULT 'm3u';

UPDATE playlists SET content_format = 'json' WHERE trim(content) GLOB '{*' OR trim(content) GLOB '[*';
