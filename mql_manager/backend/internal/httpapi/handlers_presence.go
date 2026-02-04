package httpapi

import (
	"net/http"
	"strconv"
	"time"
)

// GET /api/presence?all=1&limit=100
// Default: only online users seen within the last 90 seconds.
func (a API) handlePresence(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		w.WriteHeader(http.StatusMethodNotAllowed)
		return
	}

	q := r.URL.Query()
	all := q.Get("all") == "1" || q.Get("all") == "true"

	limit := 100
	if v := q.Get("limit"); v != "" {
		if n, err := strconv.Atoi(v); err == nil {
			limit = n
		}
	}

	cutoff := time.Now().UTC().Add(-90 * time.Second).Format(time.RFC3339)
	items, err := a.Presence.ListPresence(r.Context(), !all, cutoff, limit)
	if err != nil {
		writeError(w, http.StatusInternalServerError, err)
		return
	}

	writeJSON(w, http.StatusOK, map[string]any{
		"ok":     true,
		"cutoff": cutoff,
		"items":  items,
	})
}
