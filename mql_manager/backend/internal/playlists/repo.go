package playlists

import (
	"context"
	"database/sql"
	"errors"
	"net/url"
	"strings"
	"time"
)

type Repo struct {
	DB *sql.DB
}

type Playlist struct {
	ID         int64  `json:"id"`
	Name       string `json:"name"`
	SourceType string `json:"sourceType"` // url|inline
	SourceURL  string `json:"sourceUrl"`
	CreatedAt  string `json:"createdAt"`
}

func (r Repo) List(ctx context.Context) ([]Playlist, error) {
	rows, err := r.DB.QueryContext(ctx, `SELECT id, name, source_type, source_url, created_at FROM playlists ORDER BY id DESC`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	out := make([]Playlist, 0)
	for rows.Next() {
		var p Playlist
		if err := rows.Scan(&p.ID, &p.Name, &p.SourceType, &p.SourceURL, &p.CreatedAt); err != nil {
			return nil, err
		}
		out = append(out, p)
	}
	return out, rows.Err()
}

func (r Repo) Get(ctx context.Context, id int64) (Playlist, string, error) {
	var p Playlist
	var content string
	err := r.DB.QueryRowContext(ctx, `SELECT id, name, source_type, source_url, content, created_at FROM playlists WHERE id = ?`, id).
		Scan(&p.ID, &p.Name, &p.SourceType, &p.SourceURL, &content, &p.CreatedAt)
	return p, content, err
}

func (r Repo) CreateFromURL(ctx context.Context, name, rawURL string) (Playlist, error) {
	name = strings.TrimSpace(name)
	rawURL = strings.TrimSpace(rawURL)
	if name == "" {
		return Playlist{}, errors.New("name is required")
	}
	if rawURL == "" {
		return Playlist{}, errors.New("url is required")
	}

	parsed, err := url.Parse(rawURL)
	if err != nil || parsed.Scheme == "" || parsed.Host == "" {
		return Playlist{}, errors.New("invalid url")
	}
	if parsed.Scheme != "http" && parsed.Scheme != "https" {
		return Playlist{}, errors.New("url must be http or https")
	}

	createdAt := time.Now().UTC().Format(time.RFC3339)
	res, err := r.DB.ExecContext(ctx,
		`INSERT INTO playlists(name, source_type, source_url, content, created_at) VALUES(?, 'url', ?, '', ?)`,
		name, rawURL, createdAt,
	)
	if err != nil {
		return Playlist{}, err
	}
	id, _ := res.LastInsertId()
	p, _, err := r.Get(ctx, id)
	return p, err
}

func (r Repo) CreateInline(ctx context.Context, name, content string) (Playlist, error) {
	name = strings.TrimSpace(name)
	content = strings.TrimSpace(content)
	if name == "" {
		return Playlist{}, errors.New("name is required")
	}
	if content == "" {
		return Playlist{}, errors.New("content is required")
	}

	createdAt := time.Now().UTC().Format(time.RFC3339)
	res, err := r.DB.ExecContext(ctx,
		`INSERT INTO playlists(name, source_type, source_url, content, created_at) VALUES(?, 'inline', '', ?, ?)`,
		name, content, createdAt,
	)
	if err != nil {
		return Playlist{}, err
	}
	id, _ := res.LastInsertId()
	p, _, err := r.Get(ctx, id)
	return p, err
}

func (r Repo) Delete(ctx context.Context, id int64) error {
	_, err := r.DB.ExecContext(ctx, `DELETE FROM playlists WHERE id = ?`, id)
	return err
}
