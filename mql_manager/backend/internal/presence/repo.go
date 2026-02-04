package presence

import (
	"context"
	"database/sql"
	"errors"
	"strings"
	"time"
)

type Repo struct {
	DB *sql.DB
}

type PresenceRow struct {
	UserID       int64  `json:"userId"`
	Username     string `json:"username"`
	DisplayName  string `json:"displayName"`
	Status       string `json:"status"`
	ChannelTitle string `json:"channelTitle"`
	ChannelURL   string `json:"channelUrl"`
	LastSeenAt   string `json:"lastSeenAt"`
	UpdatedAt    string `json:"updatedAt"`
}

type Status string

const (
	StatusOnline    Status = "online"
	StatusOffline   Status = "offline"
	StatusHeartbeat Status = "heartbeat"
)

func NormalizeStatus(s string) (Status, bool) {
	v := strings.ToLower(strings.TrimSpace(s))
	switch v {
	case string(StatusOnline):
		return StatusOnline, true
	case string(StatusOffline):
		return StatusOffline, true
	case string(StatusHeartbeat):
		return StatusHeartbeat, true
	default:
		return "", false
	}
}

func (r Repo) SetPresence(ctx context.Context, userID int64, status Status, channelTitle, channelURL *string) error {
	now := time.Now().UTC().Format(time.RFC3339)

	// Treat heartbeat as online for current status.
	presenceStatus := status
	if status == StatusHeartbeat {
		presenceStatus = StatusOnline
	}

	var title any
	var url any
	if presenceStatus == StatusOffline {
		title = nil
		url = nil
	} else {
		if channelTitle != nil {
			t := strings.TrimSpace(*channelTitle)
			if t != "" {
				title = t
			}
		}
		if channelURL != nil {
			u := strings.TrimSpace(*channelURL)
			if u != "" {
				url = u
			}
		}
	}

	_, err := r.DB.ExecContext(ctx, `
INSERT INTO user_presence(user_id, status, channel_title, channel_url, last_seen_at, updated_at)
VALUES(?, ?, ?, ?, ?, ?)
ON CONFLICT(user_id) DO UPDATE SET
  status = excluded.status,
  channel_title = excluded.channel_title,
  channel_url = excluded.channel_url,
  last_seen_at = excluded.last_seen_at,
  updated_at = excluded.updated_at
`, userID, string(presenceStatus), title, url, now, now)
	return err
}

func (r Repo) AddWatchEvent(ctx context.Context, userID int64, event Status, channelTitle, channelURL *string) error {
	// Only keep meaningful events; heartbeat would spam the DB.
	if event != StatusOnline && event != StatusOffline {
		return nil
	}

	now := time.Now().UTC().Format(time.RFC3339)
	var title any
	var url any
	if channelTitle != nil {
		t := strings.TrimSpace(*channelTitle)
		if t != "" {
			title = t
		}
	}
	if channelURL != nil {
		u := strings.TrimSpace(*channelURL)
		if u != "" {
			url = u
		}
	}

	_, err := r.DB.ExecContext(ctx, `INSERT INTO watch_events(user_id, event, channel_title, channel_url, created_at) VALUES(?, ?, ?, ?, ?)`, userID, string(event), title, url, now)
	return err
}

func (r Repo) ListPresence(ctx context.Context, onlyOnline bool, onlineCutoffRFC3339 string, limit int) ([]PresenceRow, error) {
	if r.DB == nil {
		return nil, errors.New("db is nil")
	}
	if limit <= 0 {
		limit = 100
	}
	if limit > 2000 {
		limit = 2000
	}

	if onlyOnline {
		// Ensure cutoff is parseable; otherwise use default 90s.
		if strings.TrimSpace(onlineCutoffRFC3339) == "" {
			onlineCutoffRFC3339 = time.Now().UTC().Add(-90 * time.Second).Format(time.RFC3339)
		} else if _, err := time.Parse(time.RFC3339, strings.TrimSpace(onlineCutoffRFC3339)); err != nil {
			onlineCutoffRFC3339 = time.Now().UTC().Add(-90 * time.Second).Format(time.RFC3339)
		}
	}

	query := `
SELECT
  p.user_id,
  u.username,
  u.display_name,
  p.status,
  COALESCE(p.channel_title,''),
  COALESCE(p.channel_url,''),
  p.last_seen_at,
  p.updated_at
FROM user_presence p
JOIN users u ON u.id = p.user_id
`

	args := []any{}
	if onlyOnline {
		query += ` WHERE p.status = 'online' AND p.last_seen_at >= ?`
		args = append(args, onlineCutoffRFC3339)
	}

	query += ` ORDER BY p.last_seen_at DESC LIMIT ?`
	args = append(args, limit)

	rows, err := r.DB.QueryContext(ctx, query, args...)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	out := make([]PresenceRow, 0)
	for rows.Next() {
		var it PresenceRow
		if err := rows.Scan(
			&it.UserID,
			&it.Username,
			&it.DisplayName,
			&it.Status,
			&it.ChannelTitle,
			&it.ChannelURL,
			&it.LastSeenAt,
			&it.UpdatedAt,
		); err != nil {
			return nil, err
		}
		out = append(out, it)
	}
	return out, rows.Err()
}
