package auth

import (
	"net/http"
	"strings"
)

type TokenAuth struct {
	AdminToken string
	Disabled   bool
}

func (a TokenAuth) Middleware(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path == "/api/health" {
			next.ServeHTTP(w, r)
			return
		}
		if r.URL.Path == "/playlist.m3u" {
			next.ServeHTTP(w, r)
			return
		}
		if strings.HasPrefix(r.URL.Path, "/public/") {
			next.ServeHTTP(w, r)
			return
		}
		if a.Disabled {
			next.ServeHTTP(w, r)
			return
		}

		authz := r.Header.Get("Authorization")
		const prefix = "Bearer "
		if !strings.HasPrefix(authz, prefix) {
			http.Error(w, "unauthorized", http.StatusUnauthorized)
			return
		}
		token := strings.TrimSpace(strings.TrimPrefix(authz, prefix))
		if token == "" || token != a.AdminToken {
			http.Error(w, "unauthorized", http.StatusUnauthorized)
			return
		}

		next.ServeHTTP(w, r)
	})
}
