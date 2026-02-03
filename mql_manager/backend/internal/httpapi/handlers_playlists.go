package httpapi

import (
	"bytes"
	"database/sql"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"os"
	"path"
	"strconv"
	"strings"
	"time"

	"mqltv.local/mql_manager/backend/internal/channels"
)

func fetchText(ctxr *http.Request, rawURL string) (string, error) {
	client := &http.Client{Timeout: 12 * time.Second}
	req, err := http.NewRequestWithContext(ctxr.Context(), http.MethodGet, rawURL, nil)
	if err != nil {
		return "", err
	}
	resp, err := client.Do(req)
	if err != nil {
		return "", err
	}
	defer resp.Body.Close()
	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		return "", fmt.Errorf("upstream returned %s", resp.Status)
	}
	b, err := io.ReadAll(io.LimitReader(resp.Body, 10<<20))
	if err != nil {
		return "", err
	}
	return string(bytes.TrimSpace(b)), nil
}

func (a API) handlePlaylists(w http.ResponseWriter, r *http.Request) {
	switch r.Method {
	case http.MethodGet:
		items, err := a.Playlists.List(r.Context())
		if err != nil {
			writeError(w, http.StatusInternalServerError, err)
			return
		}
		// add publicUrl
		out := make([]map[string]any, 0, len(items))
		for _, p := range items {
			out = append(out, map[string]any{
				"id":         p.ID,
				"name":       p.Name,
				"sourceType": p.SourceType,
				"sourceUrl":  p.SourceURL,
				"createdAt":  p.CreatedAt,
				"publicUrl":  fmt.Sprintf("/public/m3u/%d.m3u", p.ID),
			})
		}
		writeJSON(w, http.StatusOK, out)
	case http.MethodPost:
		ct := r.Header.Get("Content-Type")
		if strings.HasPrefix(ct, "multipart/form-data") {
			a.handlePlaylistUpload(w, r)
			return
		}

		var req CreatePlaylistFromURLRequest
		if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
			writeError(w, http.StatusBadRequest, err)
			return
		}
		p, err := a.Playlists.CreateFromURL(r.Context(), strings.TrimSpace(req.Name), strings.TrimSpace(req.URL))
		if err != nil {
			writeError(w, http.StatusBadRequest, err)
			return
		}

		// Import channels now so admin can select channels.
		content, err := fetchText(r, p.SourceURL)
		if err != nil {
			writeError(w, http.StatusBadRequest, err)
			return
		}
		if _, err := a.Channels.ImportM3U(r.Context(), p.ID, content); err != nil {
			writeError(w, http.StatusBadRequest, err)
			return
		}
		writeJSON(w, http.StatusCreated, map[string]any{
			"id":         p.ID,
			"name":       p.Name,
			"sourceType": p.SourceType,
			"sourceUrl":  p.SourceURL,
			"createdAt":  p.CreatedAt,
			"publicUrl":  fmt.Sprintf("/public/m3u/%d.m3u", p.ID),
		})
	default:
		w.WriteHeader(http.StatusMethodNotAllowed)
	}
}

func (a API) handlePlaylistUpload(w http.ResponseWriter, r *http.Request) {
	if err := r.ParseMultipartForm(10 << 20); err != nil { // 10MB
		writeError(w, http.StatusBadRequest, err)
		return
	}

	name := strings.TrimSpace(r.FormValue("name"))
	file, header, err := r.FormFile("file")
	if err != nil {
		writeError(w, http.StatusBadRequest, errors.New("missing file"))
		return
	}
	defer file.Close()

	b, err := io.ReadAll(io.LimitReader(file, 10<<20))
	if err != nil {
		writeError(w, http.StatusBadRequest, err)
		return
	}
	content := string(bytes.TrimSpace(b))
	if name == "" {
		name = strings.TrimSpace(strings.TrimSuffix(header.Filename, path.Ext(header.Filename)))
	}

	p, err := a.Playlists.CreateInline(r.Context(), name, content)
	if err != nil {
		writeError(w, http.StatusBadRequest, err)
		return
	}
	if _, err := a.Channels.ImportM3U(r.Context(), p.ID, content); err != nil {
		writeError(w, http.StatusBadRequest, err)
		return
	}
	writeJSON(w, http.StatusCreated, map[string]any{
		"id":         p.ID,
		"name":       p.Name,
		"sourceType": p.SourceType,
		"sourceUrl":  p.SourceURL,
		"createdAt":  p.CreatedAt,
		"publicUrl":  fmt.Sprintf("/public/m3u/%d.m3u", p.ID),
	})
}

func (a API) handlePlaylistByID(w http.ResponseWriter, r *http.Request) {
	// /api/playlists/{id} or /api/playlists/{id}/reimport
	seg := strings.Trim(strings.TrimPrefix(r.URL.Path, "/api/playlists/"), "/")
	parts := strings.Split(seg, "/")
	if len(parts) < 1 || parts[0] == "" {
		writeError(w, http.StatusBadRequest, errors.New("invalid id"))
		return
	}
	id, err := strconv.ParseInt(parts[0], 10, 64)
	if err != nil {
		writeError(w, http.StatusBadRequest, errors.New("invalid id"))
		return
	}
	if len(parts) == 2 && parts[1] == "reimport" {
		a.handlePlaylistReimport(w, r, id)
		return
	}
	if len(parts) != 1 {
		w.WriteHeader(http.StatusNotFound)
		return
	}

	switch r.Method {
	case http.MethodDelete:
		if err := a.Playlists.Delete(r.Context(), id); err != nil {
			writeError(w, http.StatusInternalServerError, err)
			return
		}
		w.WriteHeader(http.StatusNoContent)
	default:
		w.WriteHeader(http.StatusMethodNotAllowed)
	}
}

func (a API) handlePlaylistReimport(w http.ResponseWriter, r *http.Request, playlistID int64) {
	if r.Method != http.MethodPost {
		w.WriteHeader(http.StatusMethodNotAllowed)
		return
	}

	p, content, err := a.Playlists.Get(r.Context(), playlistID)
	if err != nil {
		if err == sql.ErrNoRows {
			w.WriteHeader(http.StatusNotFound)
			return
		}
		writeError(w, http.StatusInternalServerError, err)
		return
	}

	var src string
	if p.SourceType == "url" {
		src, err = fetchText(r, p.SourceURL)
		if err != nil {
			writeError(w, http.StatusBadRequest, err)
			return
		}
	} else {
		src = strings.TrimSpace(content)
		if src == "" {
			writeError(w, http.StatusBadRequest, errors.New("playlist content is empty"))
			return
		}
	}

	n, err := a.Channels.ImportM3U(r.Context(), p.ID, src)
	if err != nil {
		writeError(w, http.StatusBadRequest, err)
		return
	}

	writeJSON(w, http.StatusOK, map[string]any{
		"ok":       true,
		"imported": n,
	})
}

func (a API) handlePublicM3UByPlaylistID(w http.ResponseWriter, r *http.Request) {
	// /public/m3u/{id}.m3u
	seg := strings.Trim(strings.TrimPrefix(r.URL.Path, "/public/m3u/"), "/")
	seg = strings.TrimSuffix(seg, ".m3u")
	id, err := strconv.ParseInt(seg, 10, 64)
	if err != nil {
		w.WriteHeader(http.StatusNotFound)
		return
	}

	p, content, err := a.Playlists.Get(r.Context(), id)
	if err != nil {
		if err == sql.ErrNoRows {
			w.WriteHeader(http.StatusNotFound)
			return
		}
		w.WriteHeader(http.StatusInternalServerError)
		return
	}

	servePlaylist(w, r, p.SourceType, p.SourceURL, content)
}

func (a API) handlePublicUserPlaylist(w http.ResponseWriter, r *http.Request) {
	// /public/users/{appKey}/playlist.m3u
	p := strings.Trim(strings.TrimPrefix(r.URL.Path, "/public/users/"), "/")
	parts := strings.Split(p, "/")
	if len(parts) != 2 {
		w.WriteHeader(http.StatusNotFound)
		return
	}
	appKey := parts[0]
	file := parts[1]
	if file != "playlist.m3u" {
		w.WriteHeader(http.StatusNotFound)
		return
	}
	a.servePublicUserPlaylistByAppKey(w, r, appKey)
}

func (a API) handlePublicRootPlaylist(w http.ResponseWriter, r *http.Request) {
	// Compatibility endpoint for older Android builds:
	// GET /playlist.m3u
	// Optional: ?appKey=.... Otherwise it will use MQLM_DEFAULT_APPKEY, or the newest user in DB.
	if r.Method != http.MethodGet {
		w.WriteHeader(http.StatusMethodNotAllowed)
		return
	}

	appKey := strings.TrimSpace(r.URL.Query().Get("appKey"))
	if appKey == "" {
		appKey = strings.TrimSpace(os.Getenv("MQLM_DEFAULT_APPKEY"))
	}
	if appKey == "" {
		// fallback: newest user
		_ = a.Users.DB.QueryRowContext(r.Context(), `SELECT COALESCE(app_key,'') FROM users WHERE COALESCE(app_key,'') != '' ORDER BY id DESC LIMIT 1`).Scan(&appKey)
		appKey = strings.TrimSpace(appKey)
	}
	if appKey == "" {
		w.WriteHeader(http.StatusNotFound)
		return
	}

	a.servePublicUserPlaylistByAppKey(w, r, appKey)
}

func (a API) servePublicUserPlaylistByAppKey(w http.ResponseWriter, r *http.Request, appKey string) {
	if strings.TrimSpace(appKey) == "" {
		w.WriteHeader(http.StatusNotFound)
		return
	}

	u, err := a.Users.GetUserByAppKey(r.Context(), appKey)
	if err != nil {
		if err == sql.ErrNoRows {
			w.WriteHeader(http.StatusNotFound)
			return
		}
		w.WriteHeader(http.StatusInternalServerError)
		return
	}

	// If user has selected channels, generate M3U from those.
	hasCh, err := a.userHasChannels(r, u.ID)
	if err != nil {
		w.WriteHeader(http.StatusInternalServerError)
		return
	}
	if hasCh {
		chs, err := a.Channels.ListUserChannels(r.Context(), u.ID)
		if err != nil {
			w.WriteHeader(http.StatusInternalServerError)
			return
		}
		writeM3UFromChannels(w, chs)
		return
	}

	// fallback: old behavior
	if u.PlaylistID == nil {
		w.WriteHeader(http.StatusNotFound)
		return
	}

	pl, content, err := a.Playlists.Get(r.Context(), *u.PlaylistID)
	if err != nil {
		if err == sql.ErrNoRows {
			w.WriteHeader(http.StatusNotFound)
			return
		}
		w.WriteHeader(http.StatusInternalServerError)
		return
	}

	servePlaylist(w, r, pl.SourceType, pl.SourceURL, content)
}

func writeM3UFromChannels(w http.ResponseWriter, chs []channels.Channel) {
	w.Header().Set("Content-Type", "application/vnd.apple.mpegurl; charset=utf-8")
	w.Header().Set("Cache-Control", "no-store")
	_, _ = io.WriteString(w, "#EXTM3U\n")
	for _, c := range chs {
		name := c.Name
		if name == "" {
			name = c.TvgName
		}
		attrs := make([]string, 0, 4)
		if c.TvgID != "" {
			attrs = append(attrs, fmt.Sprintf("tvg-id=\"%s\"", escapeAttr(c.TvgID)))
		}
		if c.TvgName != "" {
			attrs = append(attrs, fmt.Sprintf("tvg-name=\"%s\"", escapeAttr(c.TvgName)))
		}
		if c.TvgLogo != "" {
			attrs = append(attrs, fmt.Sprintf("tvg-logo=\"%s\"", escapeAttr(c.TvgLogo)))
		}
		if c.GroupTitle != "" {
			attrs = append(attrs, fmt.Sprintf("group-title=\"%s\"", escapeAttr(c.GroupTitle)))
		}
		meta := ""
		if len(attrs) > 0 {
			meta = " " + strings.Join(attrs, " ")
		}
		_, _ = io.WriteString(w, fmt.Sprintf("#EXTINF:-1%s,%s\n", meta, name))
		_, _ = io.WriteString(w, c.StreamURL+"\n")
	}
}

func escapeAttr(s string) string {
	return strings.ReplaceAll(s, "\"", "")
}

func servePlaylist(w http.ResponseWriter, r *http.Request, sourceType, sourceURL, content string) {
	w.Header().Set("Content-Type", "application/vnd.apple.mpegurl; charset=utf-8")
	w.Header().Set("Cache-Control", "no-store")

	if sourceType == "inline" {
		_, _ = io.WriteString(w, content)
		if !strings.HasSuffix(content, "\n") {
			_, _ = io.WriteString(w, "\n")
		}
		return
	}

	// sourceType=url -> proxy it
	client := &http.Client{Timeout: 12 * time.Second}
	req, err := http.NewRequestWithContext(r.Context(), http.MethodGet, sourceURL, nil)
	if err != nil {
		w.WriteHeader(http.StatusBadGateway)
		return
	}
	resp, err := client.Do(req)
	if err != nil {
		w.WriteHeader(http.StatusBadGateway)
		return
	}
	defer resp.Body.Close()

	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		w.WriteHeader(http.StatusBadGateway)
		return
	}

	_, _ = io.Copy(w, io.LimitReader(resp.Body, 10<<20))
}
