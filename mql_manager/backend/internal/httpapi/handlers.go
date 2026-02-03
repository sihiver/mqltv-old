package httpapi

import (
	"database/sql"
	"encoding/json"
	"errors"
	"net/http"
	"strconv"
	"strings"
	"time"

	"mqltv.local/mql_manager/backend/internal/channels"
	"mqltv.local/mql_manager/backend/internal/playlists"
	"mqltv.local/mql_manager/backend/internal/users"
)

type API struct {
	Users        users.Repo
	Playlists    playlists.Repo
	Channels     channels.Repo
	AuthRequired bool
}

func (a API) Register(mux *http.ServeMux) {
	mux.HandleFunc("/api/health", a.handleHealth)
	mux.HandleFunc("/api/users", a.handleUsers)
	mux.HandleFunc("/api/users/", a.handleUserByID)
	mux.HandleFunc("/api/subscriptions/", a.handleSubscriptionByID)
	mux.HandleFunc("/api/playlists", a.handlePlaylists)
	mux.HandleFunc("/api/playlists/", a.handlePlaylistByID)
	mux.HandleFunc("/api/channels", a.handleChannels)

	// Public endpoints for Android app
	mux.HandleFunc("/playlist.m3u", a.handlePublicRootPlaylist)
	mux.HandleFunc("/public/m3u/", a.handlePublicM3UByPlaylistID)
	mux.HandleFunc("/public/users/", a.handlePublicUserPlaylist)
}

func (a API) handleHealth(w http.ResponseWriter, r *http.Request) {
	writeJSON(w, http.StatusOK, map[string]any{
		"ok":           true,
		"time":         time.Now().UTC().Format(time.RFC3339),
		"authRequired": a.AuthRequired,
	})
}

func (a API) handleUsers(w http.ResponseWriter, r *http.Request) {
	switch r.Method {
	case http.MethodGet:
		items, err := a.Users.ListUsers(r.Context())
		if err != nil {
			writeError(w, http.StatusInternalServerError, err)
			return
		}
		writeJSON(w, http.StatusOK, items)
	case http.MethodPost:
		var req CreateUserRequest
		if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
			writeError(w, http.StatusBadRequest, err)
			return
		}
		u, err := a.Users.CreateUser(r.Context(), strings.TrimSpace(req.Username), strings.TrimSpace(req.DisplayName))
		if err != nil {
			writeError(w, http.StatusBadRequest, err)
			return
		}
		writeJSON(w, http.StatusCreated, u)
	default:
		w.WriteHeader(http.StatusMethodNotAllowed)
	}
}

func (a API) handleUserByID(w http.ResponseWriter, r *http.Request) {
	// /api/users/{id} or /api/users/{id}/subscriptions
	path := strings.TrimPrefix(r.URL.Path, "/api/users/")
	path = strings.Trim(path, "/")
	if path == "" {
		w.WriteHeader(http.StatusNotFound)
		return
	}

	parts := strings.Split(path, "/")
	id, err := strconv.ParseInt(parts[0], 10, 64)
	if err != nil {
		writeError(w, http.StatusBadRequest, errors.New("invalid id"))
		return
	}

	if len(parts) == 2 && parts[1] == "subscriptions" {
		a.handleUserSubscriptions(w, r, id)
		return
	}
	if len(parts) == 2 && parts[1] == "playlist" {
		a.handleUserPlaylist(w, r, id)
		return
	}
	if len(parts) == 2 && parts[1] == "channels" {
		a.handleUserChannels(w, r, id)
		return
	}
	if len(parts) != 1 {
		w.WriteHeader(http.StatusNotFound)
		return
	}

	switch r.Method {
	case http.MethodGet:
		u, err := a.Users.GetUser(r.Context(), id)
		if err != nil {
			if err == sql.ErrNoRows {
				w.WriteHeader(http.StatusNotFound)
				return
			}
			writeError(w, http.StatusInternalServerError, err)
			return
		}
		writeJSON(w, http.StatusOK, u)
	case http.MethodPut:
		var req UpdateUserRequest
		if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
			writeError(w, http.StatusBadRequest, err)
			return
		}
		u, err := a.Users.UpdateUser(r.Context(), id, trimPtr(req.Username), trimPtr(req.DisplayName))
		if err != nil {
			writeError(w, http.StatusBadRequest, err)
			return
		}
		writeJSON(w, http.StatusOK, u)
	case http.MethodDelete:
		if err := a.Users.DeleteUser(r.Context(), id); err != nil {
			writeError(w, http.StatusInternalServerError, err)
			return
		}
		w.WriteHeader(http.StatusNoContent)
	default:
		w.WriteHeader(http.StatusMethodNotAllowed)
	}
}

func (a API) handleUserPlaylist(w http.ResponseWriter, r *http.Request, userID int64) {
	switch r.Method {
	case http.MethodPut:
		var req SetUserPlaylistRequest
		if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
			writeError(w, http.StatusBadRequest, err)
			return
		}
		if err := a.Users.SetUserPlaylist(r.Context(), userID, req.PlaylistID); err != nil {
			writeError(w, http.StatusInternalServerError, err)
			return
		}
		u, err := a.Users.GetUser(r.Context(), userID)
		if err != nil {
			writeError(w, http.StatusInternalServerError, err)
			return
		}
		writeJSON(w, http.StatusOK, u)
	default:
		w.WriteHeader(http.StatusMethodNotAllowed)
	}
}

func (a API) handleUserSubscriptions(w http.ResponseWriter, r *http.Request, userID int64) {
	switch r.Method {
	case http.MethodGet:
		items, err := a.Users.ListSubscriptionsByUser(r.Context(), userID)
		if err != nil {
			writeError(w, http.StatusInternalServerError, err)
			return
		}
		writeJSON(w, http.StatusOK, items)
	case http.MethodPost:
		var req CreateSubscriptionRequest
		if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
			writeError(w, http.StatusBadRequest, err)
			return
		}
		s, err := a.Users.CreateSubscription(r.Context(), userID, strings.TrimSpace(req.Plan), strings.TrimSpace(req.ExpiresAt))
		if err != nil {
			writeError(w, http.StatusBadRequest, err)
			return
		}
		writeJSON(w, http.StatusCreated, s)
	default:
		w.WriteHeader(http.StatusMethodNotAllowed)
	}
}

func (a API) handleSubscriptionByID(w http.ResponseWriter, r *http.Request) {
	// /api/subscriptions/{id}
	path := strings.TrimPrefix(r.URL.Path, "/api/subscriptions/")
	path = strings.Trim(path, "/")
	id, err := strconv.ParseInt(path, 10, 64)
	if err != nil {
		writeError(w, http.StatusBadRequest, errors.New("invalid id"))
		return
	}

	switch r.Method {
	case http.MethodDelete:
		if err := a.Users.DeleteSubscription(r.Context(), id); err != nil {
			writeError(w, http.StatusInternalServerError, err)
			return
		}
		w.WriteHeader(http.StatusNoContent)
	default:
		w.WriteHeader(http.StatusMethodNotAllowed)
	}
}

func writeJSON(w http.ResponseWriter, code int, v any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(code)
	_ = json.NewEncoder(w).Encode(v)
}

func writeError(w http.ResponseWriter, code int, err error) {
	writeJSON(w, code, map[string]any{"error": err.Error()})
}

func trimPtr(s *string) *string {
	if s == nil {
		return nil
	}
	v := strings.TrimSpace(*s)
	return &v
}
