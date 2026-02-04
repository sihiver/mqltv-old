package httpapi

import (
	"encoding/json"
	"errors"
	"net/http"
	"strings"

	"mqltv.local/mql_manager/backend/internal/presence"
)

type PublicPresenceRequest struct {
	AppKey       string `json:"appKey"`
	Status       string `json:"status"` // online | offline | heartbeat
	ChannelTitle string `json:"channelTitle"`
	ChannelURL   string `json:"channelUrl"`
}

func (a API) handlePublicPresence(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		w.WriteHeader(http.StatusMethodNotAllowed)
		return
	}

	var req PublicPresenceRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeError(w, http.StatusBadRequest, err)
		return
	}

	appKey := strings.TrimSpace(req.AppKey)
	if appKey == "" {
		writeError(w, http.StatusBadRequest, errors.New("appKey is required"))
		return
	}

	st, ok := presence.NormalizeStatus(req.Status)
	if !ok {
		writeError(w, http.StatusBadRequest, errors.New("invalid status"))
		return
	}

	u, err := a.Users.GetUserByAppKey(r.Context(), appKey)
	if err != nil {
		writeError(w, http.StatusUnauthorized, errors.New("invalid appKey"))
		return
	}

	var titlePtr *string
	var urlPtr *string
	if strings.TrimSpace(req.ChannelTitle) != "" {
		t := req.ChannelTitle
		titlePtr = &t
	}
	if strings.TrimSpace(req.ChannelURL) != "" {
		u := req.ChannelURL
		urlPtr = &u
	}

	if err := a.Presence.SetPresence(r.Context(), u.ID, st, titlePtr, urlPtr); err != nil {
		writeError(w, http.StatusInternalServerError, err)
		return
	}
	_ = a.Presence.AddWatchEvent(r.Context(), u.ID, st, titlePtr, urlPtr)

	writeJSON(w, http.StatusOK, map[string]any{"ok": true})
}
