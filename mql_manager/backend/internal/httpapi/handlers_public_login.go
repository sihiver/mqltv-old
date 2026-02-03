package httpapi

import (
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

	writeJSON(w, http.StatusOK, map[string]any{
		"ok":                true,
		"user":              u,
		"publicPlaylistUrl": fmt.Sprintf("/public/users/%s/playlist.m3u", u.AppKey),
	})
}
