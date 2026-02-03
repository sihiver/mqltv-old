package httpapi

import (
	"database/sql"
	"encoding/json"
	"errors"
	"net/http"
	"strconv"
	"strings"
)

func (a API) handlePackages(w http.ResponseWriter, r *http.Request) {
	switch r.Method {
	case http.MethodGet:
		items, err := a.Packages.List(r.Context())
		if err != nil {
			writeError(w, http.StatusInternalServerError, err)
			return
		}
		writeJSON(w, http.StatusOK, items)
	case http.MethodPost:
		var req CreatePackageRequest
		if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
			writeError(w, http.StatusBadRequest, err)
			return
		}
		p, err := a.Packages.Create(r.Context(), req.Name, req.Price)
		if err != nil {
			writeError(w, http.StatusBadRequest, err)
			return
		}
		writeJSON(w, http.StatusCreated, p)
	default:
		w.WriteHeader(http.StatusMethodNotAllowed)
	}
}

func (a API) handlePackageByID(w http.ResponseWriter, r *http.Request) {
	// /api/packages/{id} or /api/packages/{id}/channels
	path := strings.TrimPrefix(r.URL.Path, "/api/packages/")
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

	if len(parts) == 2 && parts[1] == "channels" {
		a.handlePackageChannels(w, r, id)
		return
	}
	if len(parts) != 1 {
		w.WriteHeader(http.StatusNotFound)
		return
	}

	switch r.Method {
	case http.MethodGet:
		p, err := a.Packages.Get(r.Context(), id)
		if err != nil {
			if err == sql.ErrNoRows {
				w.WriteHeader(http.StatusNotFound)
				return
			}
			writeError(w, http.StatusInternalServerError, err)
			return
		}
		writeJSON(w, http.StatusOK, p)
	case http.MethodDelete:
		if err := a.Packages.Delete(r.Context(), id); err != nil {
			writeError(w, http.StatusInternalServerError, err)
			return
		}
		w.WriteHeader(http.StatusNoContent)
	default:
		w.WriteHeader(http.StatusMethodNotAllowed)
	}
}

func (a API) handlePackageChannels(w http.ResponseWriter, r *http.Request, packageID int64) {
	switch r.Method {
	case http.MethodGet:
		items, err := a.Packages.ListChannels(r.Context(), packageID)
		if err != nil {
			writeError(w, http.StatusInternalServerError, err)
			return
		}
		writeJSON(w, http.StatusOK, items)
	case http.MethodPut:
		var req SetPackageChannelsRequest
		if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
			writeError(w, http.StatusBadRequest, err)
			return
		}
		if err := a.Packages.SetChannels(r.Context(), packageID, req.ChannelIDs); err != nil {
			writeError(w, http.StatusInternalServerError, err)
			return
		}
		items, err := a.Packages.ListChannels(r.Context(), packageID)
		if err != nil {
			writeError(w, http.StatusInternalServerError, err)
			return
		}
		writeJSON(w, http.StatusOK, items)
	default:
		w.WriteHeader(http.StatusMethodNotAllowed)
	}
}
