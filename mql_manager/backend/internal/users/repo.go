package users

import (
	"context"
	"crypto/rand"
	"database/sql"
	"encoding/hex"
	"errors"
	"time"
)

type User struct {
	ID          int64  `json:"id"`
	Username    string `json:"username"`
	DisplayName string `json:"displayName"`
	AppKey      string `json:"appKey"`
	PlaylistID  *int64 `json:"playlistId"`
	CreatedAt   string `json:"createdAt"`
}

type Subscription struct {
	ID        int64  `json:"id"`
	UserID    int64  `json:"userId"`
	Plan      string `json:"plan"`
	ExpiresAt string `json:"expiresAt"`
	CreatedAt string `json:"createdAt"`
}

type Repo struct {
	DB *sql.DB
}

func (r Repo) ListUsers(ctx context.Context) ([]User, error) {
	rows, err := r.DB.QueryContext(ctx, `SELECT id, username, display_name, COALESCE(app_key,''), playlist_id, created_at FROM users ORDER BY id DESC`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	out := make([]User, 0)
	for rows.Next() {
		var u User
		var playlistID sql.NullInt64
		if err := rows.Scan(&u.ID, &u.Username, &u.DisplayName, &u.AppKey, &playlistID, &u.CreatedAt); err != nil {
			return nil, err
		}
		if playlistID.Valid {
			v := playlistID.Int64
			u.PlaylistID = &v
		}
		out = append(out, u)
	}
	return out, rows.Err()
}

func (r Repo) GetUser(ctx context.Context, id int64) (User, error) {
	var u User
	var playlistID sql.NullInt64
	err := r.DB.QueryRowContext(ctx, `SELECT id, username, display_name, COALESCE(app_key,''), playlist_id, created_at FROM users WHERE id = ?`, id).
		Scan(&u.ID, &u.Username, &u.DisplayName, &u.AppKey, &playlistID, &u.CreatedAt)
	if playlistID.Valid {
		v := playlistID.Int64
		u.PlaylistID = &v
	}
	return u, err
}

func (r Repo) CreateUser(ctx context.Context, username, displayName string) (User, error) {
	if username == "" {
		return User{}, errors.New("username is required")
	}

	appKey, err := newAppKey()
	if err != nil {
		return User{}, err
	}

	createdAt := time.Now().UTC().Format(time.RFC3339)
	res, err := r.DB.ExecContext(ctx, `INSERT INTO users(username, display_name, app_key, playlist_id, created_at) VALUES(?, ?, ?, NULL, ?)`, username, displayName, appKey, createdAt)
	if err != nil {
		return User{}, err
	}
	id, err := res.LastInsertId()
	if err != nil {
		return User{}, err
	}
	return r.GetUser(ctx, id)
}

func (r Repo) SetUserPlaylist(ctx context.Context, userID int64, playlistID *int64) error {
	if playlistID == nil {
		_, err := r.DB.ExecContext(ctx, `UPDATE users SET playlist_id = NULL WHERE id = ?`, userID)
		return err
	}
	_, err := r.DB.ExecContext(ctx, `UPDATE users SET playlist_id = ? WHERE id = ?`, *playlistID, userID)
	return err
}

func (r Repo) GetUserByAppKey(ctx context.Context, appKey string) (User, error) {
	var u User
	var playlistID sql.NullInt64
	err := r.DB.QueryRowContext(ctx, `SELECT id, username, display_name, COALESCE(app_key,''), playlist_id, created_at FROM users WHERE app_key = ?`, appKey).
		Scan(&u.ID, &u.Username, &u.DisplayName, &u.AppKey, &playlistID, &u.CreatedAt)
	if playlistID.Valid {
		v := playlistID.Int64
		u.PlaylistID = &v
	}
	return u, err
}

func newAppKey() (string, error) {
	b := make([]byte, 16)
	if _, err := rand.Read(b); err != nil {
		return "", err
	}
	return hex.EncodeToString(b), nil
}

func (r Repo) UpdateUser(ctx context.Context, id int64, username, displayName *string) (User, error) {
	if username != nil {
		if *username == "" {
			return User{}, errors.New("username cannot be empty")
		}
		if _, err := r.DB.ExecContext(ctx, `UPDATE users SET username = ? WHERE id = ?`, *username, id); err != nil {
			return User{}, err
		}
	}
	if displayName != nil {
		if _, err := r.DB.ExecContext(ctx, `UPDATE users SET display_name = ? WHERE id = ?`, *displayName, id); err != nil {
			return User{}, err
		}
	}
	return r.GetUser(ctx, id)
}

func (r Repo) DeleteUser(ctx context.Context, id int64) error {
	_, err := r.DB.ExecContext(ctx, `DELETE FROM users WHERE id = ?`, id)
	return err
}

func (r Repo) ListSubscriptionsByUser(ctx context.Context, userID int64) ([]Subscription, error) {
	rows, err := r.DB.QueryContext(ctx, `SELECT id, user_id, plan, expires_at, created_at FROM subscriptions WHERE user_id = ? ORDER BY id DESC`, userID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	out := make([]Subscription, 0)
	for rows.Next() {
		var s Subscription
		if err := rows.Scan(&s.ID, &s.UserID, &s.Plan, &s.ExpiresAt, &s.CreatedAt); err != nil {
			return nil, err
		}
		out = append(out, s)
	}
	return out, rows.Err()
}

func (r Repo) CreateSubscription(ctx context.Context, userID int64, plan, expiresAt string) (Subscription, error) {
	if plan == "" {
		return Subscription{}, errors.New("plan is required")
	}
	if expiresAt == "" {
		return Subscription{}, errors.New("expiresAt is required")
	}

	createdAt := time.Now().UTC().Format(time.RFC3339)
	res, err := r.DB.ExecContext(ctx, `INSERT INTO subscriptions(user_id, plan, expires_at, created_at) VALUES(?, ?, ?, ?)`, userID, plan, expiresAt, createdAt)
	if err != nil {
		return Subscription{}, err
	}
	id, err := res.LastInsertId()
	if err != nil {
		return Subscription{}, err
	}

	var s Subscription
	err = r.DB.QueryRowContext(ctx, `SELECT id, user_id, plan, expires_at, created_at FROM subscriptions WHERE id = ?`, id).
		Scan(&s.ID, &s.UserID, &s.Plan, &s.ExpiresAt, &s.CreatedAt)
	return s, err
}

func (r Repo) DeleteSubscription(ctx context.Context, id int64) error {
	_, err := r.DB.ExecContext(ctx, `DELETE FROM subscriptions WHERE id = ?`, id)
	return err
}
