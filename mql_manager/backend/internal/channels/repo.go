package channels

import (
	"bufio"
	"context"
	"database/sql"
	"errors"
	"fmt"
	"io"
	"regexp"
	"strings"
	"time"
)

var attrRe = regexp.MustCompile(`([A-Za-z0-9_-]+)="([^"]*)"`)

type Repo struct {
	DB *sql.DB
}

type Channel struct {
	ID         int64  `json:"id"`
	Name       string `json:"name"`
	StreamURL  string `json:"streamUrl"`
	TvgID      string `json:"tvgId"`
	TvgName    string `json:"tvgName"`
	TvgLogo    string `json:"tvgLogo"`
	GroupTitle string `json:"groupTitle"`
	SourceID   string `json:"sourceId"`
	ExtraJSON  string `json:"extraJson"`
	CreatedAt  string `json:"createdAt"`
}

type m3uItem struct {
	Name       string
	StreamURL  string
	SourceID   string
	TvgID      string
	TvgName    string
	TvgLogo    string
	GroupTitle string
	ExtraJSON  string
}

func (r Repo) ImportM3U(ctx context.Context, playlistID int64, content string) (int, error) {
	if playlistID <= 0 {
		return 0, errors.New("invalid playlist id")
	}
	content = strings.TrimSpace(content)
	if content == "" {
		return 0, errors.New("content is required")
	}
	items, err := parsePlaylistContent(content)
	if err != nil {
		return 0, err
	}

	tx, err := r.DB.BeginTx(ctx, nil)
	if err != nil {
		return 0, err
	}
	defer func() { _ = tx.Rollback() }()

	var playlistExists int
	if err := tx.QueryRowContext(ctx, `SELECT 1 FROM playlists WHERE id = ? LIMIT 1`, playlistID).Scan(&playlistExists); err != nil {
		if errors.Is(err, sql.ErrNoRows) {
			return 0, fmt.Errorf("playlist %d tidak ditemukan", playlistID)
		}
		return 0, err
	}

	if _, err := tx.ExecContext(ctx, `DELETE FROM playlist_channels WHERE playlist_id = ?`, playlistID); err != nil {
		return 0, err
	}

	createdAt := time.Now().UTC().Format(time.RFC3339)
	imported := 0
	for i, it := range items {
		if it.StreamURL == "" {
			continue
		}

		channelID, err := upsertChannelTx(ctx, tx, it, createdAt)
		if err != nil {
			return imported, err
		}
		if channelID <= 0 {
			return imported, fmt.Errorf("channel id tidak valid untuk url %q", it.StreamURL)
		}

		if _, err := tx.ExecContext(ctx, `INSERT OR REPLACE INTO playlist_channels(playlist_id, channel_id, pos) VALUES(?, ?, ?)`, playlistID, channelID, i); err != nil {
			return imported, err
		}
		imported++
	}

	if err := tx.Commit(); err != nil {
		return imported, err
	}
	return imported, nil
}

func (r Repo) ListChannels(ctx context.Context, playlistID *int64, q string, limit int) ([]Channel, error) {
	q = strings.TrimSpace(q)
	if limit <= 0 || limit > 2000 {
		limit = 2000
	}

	args := make([]any, 0, 4)
	where := make([]string, 0, 4)

	join := ""
	if playlistID != nil {
		join = "JOIN playlist_channels pc ON pc.channel_id = c.id"
		where = append(where, "pc.playlist_id = ?")
		args = append(args, *playlistID)
	}
	if q != "" {
		where = append(where, "(LOWER(c.name) LIKE ? OR LOWER(c.group_title) LIKE ?)")
		qq := "%" + strings.ToLower(q) + "%"
		args = append(args, qq, qq)
	}

	w := ""
	if len(where) > 0 {
		w = "WHERE " + strings.Join(where, " AND ")
	}

	order := "ORDER BY c.group_title ASC, c.name ASC"
	if playlistID != nil {
		order = "ORDER BY pc.pos ASC"
	}

	query := `SELECT ` + channelSelectSQL + `
FROM channels c ` + join + " " + w + " " + order + ` LIMIT ?`
	args = append(args, limit)

	rows, err := r.DB.QueryContext(ctx, query, args...)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	out := make([]Channel, 0)
	for rows.Next() {
		c, err := ScanRow(rows)
		if err != nil {
			return nil, err
		}
		out = append(out, c)
	}
	return out, rows.Err()
}

func (r Repo) ListUserChannels(ctx context.Context, userID int64) ([]Channel, error) {
	rows, err := r.DB.QueryContext(ctx, `
SELECT `+channelSelectSQL+`
FROM user_channels uc
JOIN channels c ON c.id = uc.channel_id
WHERE uc.user_id = ?
ORDER BY c.group_title ASC, c.name ASC
`, userID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	out := make([]Channel, 0)
	for rows.Next() {
		c, err := ScanRow(rows)
		if err != nil {
			return nil, err
		}
		out = append(out, c)
	}
	return out, rows.Err()
}

func upsertChannelTx(ctx context.Context, tx *sql.Tx, it m3uItem, createdAt string) (int64, error) {
	sourceID := strings.TrimSpace(it.SourceID)
	extraJSON := strings.TrimSpace(it.ExtraJSON)

	if sourceID != "" {
		var existingID int64
		err := tx.QueryRowContext(ctx, `SELECT id FROM channels WHERE source_id = ?`, sourceID).Scan(&existingID)
		if err != nil && !errors.Is(err, sql.ErrNoRows) {
			return 0, err
		}
		if err == nil {
			if _, err := tx.ExecContext(ctx, `
UPDATE channels SET
  name=?, stream_url=?, tvg_id=?, tvg_name=?, tvg_logo=?, group_title=?, extra_json=?
WHERE id=?
`, it.Name, it.StreamURL, it.TvgID, it.TvgName, it.TvgLogo, it.GroupTitle, extraJSON, existingID); err != nil {
				return 0, err
			}
			return existingID, nil
		}

		var channelID int64
		err = tx.QueryRowContext(ctx, `
INSERT INTO channels(name, stream_url, tvg_id, tvg_name, tvg_logo, group_title, source_id, extra_json, created_at)
VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?)
RETURNING id
`, it.Name, it.StreamURL, it.TvgID, it.TvgName, it.TvgLogo, it.GroupTitle, sourceID, extraJSON, createdAt).Scan(&channelID)
		if err != nil {
			return 0, err
		}
		return channelID, nil
	}

	// M3U tanpa source_id: upsert by stream_url (ambil row pertama jika duplikat).
	var channelID int64
	err := tx.QueryRowContext(ctx, `SELECT id FROM channels WHERE stream_url = ? AND source_id = '' LIMIT 1`, it.StreamURL).Scan(&channelID)
	if err != nil && !errors.Is(err, sql.ErrNoRows) {
		return 0, err
	}
	if err == nil {
		if _, err := tx.ExecContext(ctx, `
UPDATE channels SET name=?, tvg_id=?, tvg_name=?, tvg_logo=?, group_title=?, extra_json=?
WHERE id=?
`, it.Name, it.TvgID, it.TvgName, it.TvgLogo, it.GroupTitle, extraJSON, channelID); err != nil {
			return 0, err
		}
		return channelID, nil
	}

	err = tx.QueryRowContext(ctx, `
INSERT INTO channels(name, stream_url, tvg_id, tvg_name, tvg_logo, group_title, source_id, extra_json, created_at)
VALUES(?, ?, ?, ?, ?, ?, '', ?, ?)
RETURNING id
`, it.Name, it.StreamURL, it.TvgID, it.TvgName, it.TvgLogo, it.GroupTitle, extraJSON, createdAt).Scan(&channelID)
	if err != nil {
		return 0, err
	}
	if channelID <= 0 {
		return 0, fmt.Errorf("channel id tidak valid untuk url %q", it.StreamURL)
	}
	return channelID, nil
}

func (r Repo) SetUserChannels(ctx context.Context, userID int64, channelIDs []int64) error {
	tx, err := r.DB.BeginTx(ctx, nil)
	if err != nil {
		return err
	}
	defer func() { _ = tx.Rollback() }()

	if _, err := tx.ExecContext(ctx, `DELETE FROM user_channels WHERE user_id = ?`, userID); err != nil {
		return err
	}
	for _, id := range channelIDs {
		if id <= 0 {
			continue
		}
		if _, err := tx.ExecContext(ctx, `INSERT OR IGNORE INTO user_channels(user_id, channel_id) VALUES(?, ?)`, userID, id); err != nil {
			return err
		}
	}
	return tx.Commit()
}

func parseM3U(r io.Reader) ([]m3uItem, error) {
	s := bufio.NewScanner(r)
	buf := make([]byte, 0, 64*1024)
	s.Buffer(buf, 1024*1024)

	var out []m3uItem
	var pending *m3uItem
	var lastGroup string

	for s.Scan() {
		line := strings.TrimSpace(s.Text())
		if line == "" {
			continue
		}
		if strings.HasPrefix(line, "#EXTM3U") {
			continue
		}
		if strings.HasPrefix(line, "#EXTGRP:") {
			lastGroup = strings.TrimSpace(strings.TrimPrefix(line, "#EXTGRP:"))
			continue
		}
		if strings.HasPrefix(line, "#EXTINF:") {
			item := parseExtInf(line)
			if item.GroupTitle == "" {
				item.GroupTitle = lastGroup
			}
			// Some M3U variants include the stream URL on the same line as EXTINF.
			if item.StreamURL != "" {
				if item.Name == "" {
					item.Name = item.TvgName
				}
				if item.Name == "" {
					item.Name = "Channel"
				}
				out = append(out, item)
				pending = nil
				continue
			}
			pending = &item
			continue
		}
		if strings.HasPrefix(line, "#") {
			continue
		}

		if pending == nil {
			continue
		}
		pending.StreamURL = line
		if pending.Name == "" {
			pending.Name = pending.TvgName
		}
		if pending.Name == "" {
			pending.Name = "Channel"
		}
		out = append(out, *pending)
		pending = nil
	}
	if err := s.Err(); err != nil {
		return nil, err
	}
	return out, nil
}

func parseExtInf(line string) m3uItem {
	line = strings.TrimSpace(strings.TrimPrefix(line, "#EXTINF:"))

	name := ""
	if idx := strings.Index(line, ","); idx >= 0 {
		name = strings.TrimSpace(line[idx+1:])
		line = strings.TrimSpace(line[:idx])
	}

	attrs := parseAttributes(line)
	it := m3uItem{
		Name:       name,
		StreamURL:  "",
		TvgID:      attrs["tvg-id"],
		TvgName:    attrs["tvg-name"],
		TvgLogo:    attrs["tvg-logo"],
		GroupTitle: attrs["group-title"],
	}

	// Handle inline URL placed after the channel name on the same EXTINF line.
	// Example: #EXTINF:-1 ... ,SCTV HD http://example/stream
	if it.Name != "" {
		if u := findInlineURL(it.Name); u != "" {
			idx := strings.Index(it.Name, u)
			if idx >= 0 {
				it.Name = strings.TrimSpace(it.Name[:idx])
				it.StreamURL = strings.TrimSpace(u)
			}
		}
	}
	if it.Name == "" {
		it.Name = it.TvgName
	}
	return it
}

func parseAttributes(s string) map[string]string {
	out := map[string]string{}
	for _, m := range attrRe.FindAllStringSubmatch(s, -1) {
		if len(m) != 3 {
			continue
		}
		out[m[1]] = m[2]
	}
	return out
}

func findInlineURL(s string) string {
	// find first http(s):// occurrence
	if idx := strings.Index(s, "http://"); idx >= 0 {
		return s[idx:]
	}
	if idx := strings.Index(s, "https://"); idx >= 0 {
		return s[idx:]
	}
	return ""
}
