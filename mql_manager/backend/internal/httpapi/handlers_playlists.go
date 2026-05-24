package httpapi

import (
	"bytes"
	"context"
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
	"mqltv.local/mql_manager/backend/internal/playlists"
	"mqltv.local/mql_manager/backend/internal/users"
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
		out := make([]map[string]any, 0, len(items))
		for _, p := range items {
			out = append(out, playlistPublicResponse(p))
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
		if err := a.importPlaylistM3U(r.Context(), p.ID, content); err != nil {
			writeError(w, http.StatusBadRequest, err)
			return
		}
		writeJSON(w, http.StatusCreated, playlistPublicResponse(p))
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
	if err := a.importPlaylistM3U(r.Context(), p.ID, content); err != nil {
		writeError(w, http.StatusBadRequest, err)
		return
	}
	writeJSON(w, http.StatusCreated, playlistPublicResponse(p))
}

func playlistPublicResponse(p playlists.Playlist) map[string]any {
	m3uURL := fmt.Sprintf("/public/m3u/%d.m3u", p.ID)
	jsonURL := fmt.Sprintf("/public/json/%d.json", p.ID)
	publicURL := m3uURL
	if p.ContentFormat == "json" {
		publicURL = jsonURL
	}
	return map[string]any{
		"id":            p.ID,
		"name":          p.Name,
		"sourceType":    p.SourceType,
		"sourceUrl":     p.SourceURL,
		"contentFormat": p.ContentFormat,
		"createdAt":     p.CreatedAt,
		"publicUrl":     publicURL,
		"publicM3uUrl":  m3uURL,
		"publicJsonUrl": jsonURL,
	}
}

func playlistServesJSON(p playlists.Playlist, content string) bool {
	if p.ContentFormat == "json" {
		return true
	}
	return channels.IsJSONPlaylistContent(content)
}

// importPlaylistM3U imports channels and removes the playlist row if import fails
// (avoids orphan playlists after a failed upload).
func (a API) importPlaylistM3U(ctx context.Context, playlistID int64, content string) error {
	if _, err := a.Channels.ImportM3U(ctx, playlistID, content); err != nil {
		_ = a.Playlists.Delete(ctx, playlistID)
		return err
	}
	return nil
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

	// Playlist JSON: default JSON (meski path .m3u). Tambahkan ?format=m3u untuk player IPTV.
	if playlistServesJSON(p, content) && strings.ToLower(strings.TrimSpace(r.URL.Query().Get("format"))) != "m3u" {
		a.servePublicPlaylistJSON(w, r, id, content)
		return
	}

	chs, err := a.Channels.ListChannels(r.Context(), &id, "", 50000)
	if err == nil && len(chs) > 0 {
		w.Header().Set("Content-Type", "application/vnd.apple.mpegurl; charset=utf-8")
		w.Header().Set("Cache-Control", "no-store")
		_ = channels.WriteM3U(w, chs)
		return
	}

	servePlaylist(w, r, p.SourceType, p.SourceURL, content)
}

func (a API) handlePublicJSONByPlaylistID(w http.ResponseWriter, r *http.Request) {
	// /public/json/{id}.json — export Vision+ format dengan semua field tersimpan.
	seg := strings.Trim(strings.TrimPrefix(r.URL.Path, "/public/json/"), "/")
	seg = strings.TrimSuffix(seg, ".json")
	id, err := strconv.ParseInt(seg, 10, 64)
	if err != nil {
		w.WriteHeader(http.StatusNotFound)
		return
	}

	_, content, err := a.Playlists.Get(r.Context(), id)
	if err != nil {
		if err == sql.ErrNoRows {
			w.WriteHeader(http.StatusNotFound)
			return
		}
		w.WriteHeader(http.StatusInternalServerError)
		return
	}
	a.servePublicPlaylistJSON(w, r, id, content)
}

func (a API) servePublicPlaylistJSON(w http.ResponseWriter, r *http.Request, playlistID int64, storedContent string) {
	chs, err := a.Channels.ListChannels(r.Context(), &playlistID, "", 50000)
	if err == nil && len(chs) > 0 {
		w.Header().Set("Content-Type", "application/json; charset=utf-8")
		w.Header().Set("Cache-Control", "no-store")
		countryName, country := parsePlaylistCountryMeta(storedContent)
		_ = channels.WriteVisionPlusJSON(w, chs, countryName, country)
		return
	}

	if channels.IsJSONPlaylistContent(storedContent) {
		w.Header().Set("Content-Type", "application/json; charset=utf-8")
		w.Header().Set("Cache-Control", "no-store")
		_, _ = io.WriteString(w, storedContent)
		if !strings.HasSuffix(strings.TrimSpace(storedContent), "\n") {
			_, _ = io.WriteString(w, "\n")
		}
		return
	}

	w.WriteHeader(http.StatusNotFound)
}

func parsePlaylistCountryMeta(content string) (countryName, country string) {
	content = strings.TrimSpace(content)
	if !strings.HasPrefix(content, "{") {
		return "", ""
	}
	var root struct {
		CountryName string `json:"country_name"`
		Country     string `json:"country"`
	}
	if err := json.Unmarshal([]byte(content), &root); err != nil {
		return "", ""
	}
	return strings.TrimSpace(root.CountryName), strings.TrimSpace(root.Country)
}

func (a API) handlePublicUserPlaylist(w http.ResponseWriter, r *http.Request) {
	// /public/users/{appKey}/playlist.m3u
	// /public/users/{appKey}/status
	p := strings.Trim(strings.TrimPrefix(r.URL.Path, "/public/users/"), "/")
	parts := strings.Split(p, "/")
	if len(parts) != 2 {
		w.WriteHeader(http.StatusNotFound)
		return
	}
	appKey := parts[0]
	file := parts[1]
	if file == "playlist.m3u" || file == "playlist.json" {
		a.servePublicUserPlaylistByAppKey(w, r, appKey)
		return
	}
	if file == "status" {
		a.servePublicUserStatusByAppKey(w, r, appKey)
		return
	}
	w.WriteHeader(http.StatusNotFound)
}

func (a API) servePublicUserStatusByAppKey(w http.ResponseWriter, r *http.Request, appKey string) {
	if r.Method != http.MethodGet {
		w.WriteHeader(http.StatusMethodNotAllowed)
		return
	}
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

	// Package names
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

	// Latest subscription plan + expiry (if any)
	var subPlan sql.NullString
	var subExpiresAt sql.NullString
	if err := a.Users.DB.QueryRowContext(r.Context(), `
SELECT plan, expires_at
FROM subscriptions
WHERE user_id = ?
ORDER BY id DESC
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
		"ok":   true,
		"user": u,
	})
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

	// If user has a subscription and it is expired, block playlist.
	var latestExpires string
	_ = a.Users.DB.QueryRowContext(r.Context(), `
SELECT COALESCE(expires_at, '')
FROM subscriptions
WHERE user_id = ?
ORDER BY id DESC
LIMIT 1
`, u.ID).Scan(&latestExpires)
	latestExpires = strings.TrimSpace(latestExpires)
	if latestExpires != "" {
		if t, err := time.Parse(time.RFC3339, latestExpires); err == nil {
			if time.Now().UTC().After(t) {
				w.WriteHeader(http.StatusForbidden)
				return
			}
		}
	}

	res, ok, err := a.resolveUserPlaylist(r, u)
	if err != nil {
		w.WriteHeader(http.StatusInternalServerError)
		return
	}
	if !ok {
		w.WriteHeader(http.StatusNotFound)
		return
	}

	// Vision+ / Sihiver: kirim JSON lengkap (url_license, header_iptv, jenis, ...).
	if a.shouldServeUserPlaylistAsJSON(r, res) {
		a.servePublicUserPlaylistJSON(w, r, res)
		return
	}

	if len(res.channels) > 0 {
		writeM3UFromChannels(w, res.channels)
		return
	}

	// Playlist inline/URL tanpa channel di DB.
	if res.playlist != nil {
		servePlaylist(w, r, res.playlist.SourceType, res.playlist.SourceURL, res.storedContent)
		return
	}

	w.WriteHeader(http.StatusNotFound)
}

type userPlaylistResolve struct {
	channels      []channels.Channel
	storedContent string
	playlist      *playlists.Playlist
}

func (a API) resolveUserPlaylist(r *http.Request, u users.User) (userPlaylistResolve, bool, error) {
	ctx := r.Context()
	out := userPlaylistResolve{}

	// Packages take precedence over manual channels. Admin "Packages" mode is meant
	// to drive the public playlist; leftover user_channels rows must not hide sport+online merges.
	hasPk, err := a.userHasPackages(r, u.ID)
	if err != nil {
		return out, false, err
	}
	if hasPk {
		chs, err := a.Users.ListUserPackageChannels(ctx, u.ID)
		if err != nil {
			return out, false, err
		}
		out.channels = chs
		return out, true, nil
	}

	hasCh, err := a.userHasChannels(r, u.ID)
	if err != nil {
		return out, false, err
	}
	if hasCh {
		chs, err := a.Channels.ListUserChannels(ctx, u.ID)
		if err != nil {
			return out, false, err
		}
		out.channels = chs
		return out, true, nil
	}

	if u.PlaylistID == nil {
		return out, false, nil
	}

	pl, content, err := a.Playlists.Get(ctx, *u.PlaylistID)
	if err != nil {
		if err == sql.ErrNoRows {
			return out, false, nil
		}
		return out, false, err
	}
	out.storedContent = content
	out.playlist = &pl

	chs, err := a.Channels.ListChannels(ctx, u.PlaylistID, "", 50000)
	if err != nil {
		return out, false, err
	}
	out.channels = chs
	return out, true, nil
}

func (a API) shouldServeUserPlaylistAsJSON(r *http.Request, res userPlaylistResolve) bool {
	if strings.HasSuffix(r.URL.Path, "playlist.json") {
		return true
	}
	if strings.ToLower(strings.TrimSpace(r.URL.Query().Get("format"))) == "json" {
		return true
	}
	if strings.ToLower(strings.TrimSpace(r.URL.Query().Get("format"))) == "m3u" {
		return false
	}
	if res.playlist != nil && playlistServesJSON(*res.playlist, res.storedContent) {
		return true
	}
	if channels.IsJSONPlaylistContent(res.storedContent) && len(res.channels) == 0 {
		return true
	}
	// Mixed packages (online JSON + sport M3U): default playlist.m3u stays M3U.
	// Vision+ metadata is available via playlist.json or ?format=json.
	return false
}

func (a API) servePublicUserPlaylistJSON(w http.ResponseWriter, r *http.Request, res userPlaylistResolve) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.Header().Set("Cache-Control", "no-store")

	if len(res.channels) > 0 {
		countryName, country := parsePlaylistCountryMeta(res.storedContent)
		_ = channels.WriteVisionPlusJSON(w, res.channels, countryName, country)
		return
	}

	if channels.IsJSONPlaylistContent(res.storedContent) {
		_, _ = io.WriteString(w, res.storedContent)
		if !strings.HasSuffix(strings.TrimSpace(res.storedContent), "\n") {
			_, _ = io.WriteString(w, "\n")
		}
		return
	}

	w.WriteHeader(http.StatusNotFound)
}

func writeM3UFromChannels(w http.ResponseWriter, chs []channels.Channel) {
	w.Header().Set("Content-Type", "application/vnd.apple.mpegurl; charset=utf-8")
	w.Header().Set("Cache-Control", "no-store")
	_ = channels.WriteM3U(w, chs)
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
