package webui

import (
	"embed"
	"io/fs"
	"mime"
	"net/http"
	"path"
	"strings"
)

//go:embed dist
var distFS embed.FS

// Handler serves the embedded Vite build as an SPA.
// - Static assets are served from dist/.
// - Unknown non-API routes fall back to dist/index.html.
func Handler() http.Handler {
	sub, err := fs.Sub(distFS, "dist")
	if err != nil {
		return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			http.Error(w, "webui not available", http.StatusInternalServerError)
		})
	}

	fileServer := http.FileServer(http.FS(sub))

	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		p := r.URL.Path
		if p == "" {
			p = "/"
		}
		if strings.HasPrefix(p, "/api/") || strings.HasPrefix(p, "/public/") || p == "/playlist.m3u" {
			w.WriteHeader(http.StatusNotFound)
			return
		}

		// If it looks like a file path (has an extension), serve it directly.
		ext := path.Ext(p)
		if ext != "" {
			if ct := mime.TypeByExtension(ext); ct != "" {
				w.Header().Set("Content-Type", ct)
			}
			fileServer.ServeHTTP(w, r)
			return
		}

		// SPA fallback to index.html.
		b, err := fs.ReadFile(sub, "index.html")
		if err != nil {
			http.Error(w, "index.html missing", http.StatusInternalServerError)
			return
		}
		w.Header().Set("Content-Type", "text/html; charset=utf-8")
		_, _ = w.Write(b)
	})
}
