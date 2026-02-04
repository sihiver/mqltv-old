package httpapi

import (
	"database/sql"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"strings"
)

type PublicLoginRequest struct {
	Username string `json:"username"`
	Password string `json:"password"`
}

func (a API) handlePublicLogin(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		w.WriteHeader(http.StatusMethodNotAllowed)
		return
	}

	var req PublicLoginRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeError(w, http.StatusBadRequest, err)
		return
	}

	username := strings.TrimSpace(req.Username)
	password := strings.TrimSpace(req.Password)
	if username == "" || password == "" {
		writeError(w, http.StatusBadRequest, errors.New("username and password are required"))
		return
	}

	u, err := a.Users.Authenticate(r.Context(), username, password)
	if err != nil {
		writeError(w, http.StatusUnauthorized, errors.New("invalid credentials"))
		return
	}

	// Include package names (if any) for account screen.
	var packageNames string
	_ = a.Users.DB.QueryRowContext(r.Context(), `
SELECT COALESCE(group_concat(name, '||'), '')
FROM (
	SELECT p.name AS name
	FROM user_packages up
	JOIN packages p ON p.id = up.package_id
	WHERE up.user_id = ?
	ORDER BY p.id ASC
)
`, u.ID).Scan(&packageNames)
	packageNames = strings.TrimSpace(packageNames)
	if packageNames != "" {
		u.Packages = strings.Split(packageNames, "||")
	}

	// Include latest subscription plan+expiry (if any) so clients can show account and "expired" screen.
	var subPlan sql.NullString
	var subExpiresAt sql.NullString
	if err := a.Users.DB.QueryRowContext(r.Context(), `
SELECT plan, expires_at
FROM subscriptions
WHERE user_id = ?
ORDER BY expires_at DESC
LIMIT 1
`, u.ID).Scan(&subPlan, &subExpiresAt); err == nil {
		if subPlan.Valid {
			p := strings.TrimSpace(subPlan.String)
			if p != "" {
				u.Plan = &p
			}
		}
		if subExpiresAt.Valid {
			e := strings.TrimSpace(subExpiresAt.String)
			if e != "" {
				u.ExpiresAt = &e
			}
		}
	}

	writeJSON(w, http.StatusOK, map[string]any{
		"ok":                true,
		"user":              u,
		"publicPlaylistUrl": fmt.Sprintf("/public/users/%s/playlist.m3u", u.AppKey),
	})
}
