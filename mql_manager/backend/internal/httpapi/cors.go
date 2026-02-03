package httpapi

import (
	"net/http"
	"strings"
)

type CORS struct {
	AllowedOrigins []string
}

func (c CORS) Middleware(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		origin := r.Header.Get("Origin")
		if origin != "" && c.isAllowed(origin) {
			w.Header().Set("Access-Control-Allow-Origin", origin)
			w.Header().Set("Vary", "Origin")
			w.Header().Set("Access-Control-Allow-Headers", "Authorization, Content-Type")
			w.Header().Set("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS")
		}

		if r.Method == http.MethodOptions {
			w.WriteHeader(http.StatusNoContent)
			return
		}

		next.ServeHTTP(w, r)
	})
}

func (c CORS) isAllowed(origin string) bool {
	if len(c.AllowedOrigins) == 0 {
		return false
	}
	for _, o := range c.AllowedOrigins {
		if strings.EqualFold(strings.TrimSpace(o), origin) {
			return true
		}
	}
	return false
}
