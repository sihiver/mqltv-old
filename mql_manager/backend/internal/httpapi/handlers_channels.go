package httpapi

import (
	"database/sql"
	"encoding/json"
	"errors"
	"net/http"
	"strconv"
	"strings"
)

func (a API) handleChannels(w http.ResponseWriter, r *http.Request) {
	switch r.Method {
	case http.MethodGet:
		var playlistID *int64
		if v := strings.TrimSpace(r.URL.Query().Get("playlistId")); v != "" {
			id, err := strconv.ParseInt(v, 10, 64)
			if err != nil {
				writeError(w, http.StatusBadRequest, errors.New("invalid playlistId"))
				return
			}
			playlistID = &id
		}
		q := strings.TrimSpace(r.URL.Query().Get("q"))
		items, err := a.Channels.ListChannels(r.Context(), playlistID, q, 2000)
		if err != nil {
			writeError(w, http.StatusInternalServerError, err)
			return
		}
		writeJSON(w, http.StatusOK, items)
	default:
		w.WriteHeader(http.StatusMethodNotAllowed)
	}
}

func (a API) handleUserChannels(w http.ResponseWriter, r *http.Request, userID int64) {
	switch r.Method {
	case http.MethodGet:
		items, err := a.Channels.ListUserChannels(r.Context(), userID)
		if err != nil {
			writeError(w, http.StatusInternalServerError, err)
			return
		}
		writeJSON(w, http.StatusOK, items)
	case http.MethodPut:
		var req SetUserChannelsRequest
		if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
			writeError(w, http.StatusBadRequest, err)
			return
		}
		if err := a.Channels.SetUserChannels(r.Context(), userID, req.ChannelIDs); err != nil {
			writeError(w, http.StatusInternalServerError, err)
			return
		}
		items, err := a.Channels.ListUserChannels(r.Context(), userID)
		if err != nil {
			writeError(w, http.StatusInternalServerError, err)
			return
		}
		writeJSON(w, http.StatusOK, items)
	default:
		w.WriteHeader(http.StatusMethodNotAllowed)
	}
}

func (a API) userHasChannels(ctxr *http.Request, userID int64) (bool, error) {
	// lightweight count without a new repo method
	var n int
	err := a.Channels.DB.QueryRowContext(ctxr.Context(), `SELECT COUNT(1) FROM user_channels WHERE user_id = ?`, userID).Scan(&n)
	if err != nil {
		if err == sql.ErrNoRows {
			return false, nil
		}
		return false, err
	}
	return n > 0, nil
}
