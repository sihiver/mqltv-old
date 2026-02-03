package httpapi

import (
	"encoding/json"
	"net/http"
	"strings"
)

func (a API) handleUserPassword(w http.ResponseWriter, r *http.Request, userID int64) {
	switch r.Method {
	case http.MethodPut:
		var req SetUserPasswordRequest
		if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
			writeError(w, http.StatusBadRequest, err)
			return
		}
		if err := a.Users.SetPassword(r.Context(), userID, strings.TrimSpace(req.Password)); err != nil {
			writeError(w, http.StatusBadRequest, err)
			return
		}
		writeJSON(w, http.StatusOK, map[string]any{"ok": true})
	default:
		w.WriteHeader(http.StatusMethodNotAllowed)
	}
}
