package packages

import (
	"context"
	"database/sql"
	"errors"
	"strings"
	"time"

	"mqltv.local/mql_manager/backend/internal/channels"
)

type Repo struct {
	DB *sql.DB
}

type Package struct {
	ID        int64  `json:"id"`
	Name      string `json:"name"`
	Price     int64  `json:"price"`
	CreatedAt string `json:"createdAt"`
}

func (r Repo) List(ctx context.Context) ([]Package, error) {
	rows, err := r.DB.QueryContext(ctx, `SELECT id, name, price, created_at FROM packages ORDER BY id DESC`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	out := make([]Package, 0)
	for rows.Next() {
		var p Package
		if err := rows.Scan(&p.ID, &p.Name, &p.Price, &p.CreatedAt); err != nil {
			return nil, err
		}
		out = append(out, p)
	}
	return out, rows.Err()
}

func (r Repo) Get(ctx context.Context, id int64) (Package, error) {
	var p Package
	err := r.DB.QueryRowContext(ctx, `SELECT id, name, price, created_at FROM packages WHERE id = ?`, id).
		Scan(&p.ID, &p.Name, &p.Price, &p.CreatedAt)
	return p, err
}

func (r Repo) Create(ctx context.Context, name string, price int64) (Package, error) {
	name = strings.TrimSpace(name)
	if name == "" {
		return Package{}, errors.New("name is required")
	}
	if price < 0 {
		return Package{}, errors.New("price must be >= 0")
	}

	createdAt := time.Now().UTC().Format(time.RFC3339)
	res, err := r.DB.ExecContext(ctx, `INSERT INTO packages(name, price, created_at) VALUES(?, ?, ?)`, name, price, createdAt)
	if err != nil {
		return Package{}, err
	}
	id, err := res.LastInsertId()
	if err != nil {
		return Package{}, err
	}
	return r.Get(ctx, id)
}

func (r Repo) Delete(ctx context.Context, id int64) error {
	_, err := r.DB.ExecContext(ctx, `DELETE FROM packages WHERE id = ?`, id)
	return err
}

func (r Repo) ListChannels(ctx context.Context, packageID int64) ([]channels.Channel, error) {
	rows, err := r.DB.QueryContext(ctx, `
SELECT c.id, c.name, c.stream_url, c.tvg_id, c.tvg_name, c.tvg_logo, c.group_title, c.created_at
FROM package_channels pc
JOIN channels c ON c.id = pc.channel_id
WHERE pc.package_id = ?
ORDER BY pc.pos ASC
`, packageID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	out := make([]channels.Channel, 0)
	for rows.Next() {
		var c channels.Channel
		if err := rows.Scan(&c.ID, &c.Name, &c.StreamURL, &c.TvgID, &c.TvgName, &c.TvgLogo, &c.GroupTitle, &c.CreatedAt); err != nil {
			return nil, err
		}
		out = append(out, c)
	}
	return out, rows.Err()
}

func (r Repo) SetChannels(ctx context.Context, packageID int64, channelIDs []int64) error {
	tx, err := r.DB.BeginTx(ctx, nil)
	if err != nil {
		return err
	}
	defer func() { _ = tx.Rollback() }()

	if _, err := tx.ExecContext(ctx, `DELETE FROM package_channels WHERE package_id = ?`, packageID); err != nil {
		return err
	}
	for i, id := range channelIDs {
		if id <= 0 {
			continue
		}
		if _, err := tx.ExecContext(ctx, `INSERT OR IGNORE INTO package_channels(package_id, channel_id, pos) VALUES(?, ?, ?)`, packageID, id, i); err != nil {
			return err
		}
	}
	return tx.Commit()
}
