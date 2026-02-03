package config

import (
	"errors"
	"net"
	"os"
	"strings"
)

type Config struct {
	Addr         string
	DBPath       string
	AdminToken   string
	AuthDisabled bool
	CORSOrigins  []string
}

func Load() (Config, error) {
	cfg := Config{
		Addr:       getenvDefault("MQLM_ADDR", "127.0.0.1:8080"),
		DBPath:     getenvDefault("MQLM_DB_PATH", "./data/mql_manager.db"),
		AdminToken: os.Getenv("MQLM_ADMIN_TOKEN"),
	}

	origins := strings.TrimSpace(os.Getenv("MQLM_CORS_ORIGINS"))
	if origins != "" {
		parts := strings.Split(origins, ",")
		for _, p := range parts {
			o := strings.TrimSpace(p)
			if o != "" {
				cfg.CORSOrigins = append(cfg.CORSOrigins, o)
			}
		}
	}

	if strings.TrimSpace(cfg.AdminToken) == "" {
		// Allow no-auth mode for local development only.
		host, _, err := net.SplitHostPort(cfg.Addr)
		if err != nil {
			return Config{}, errors.New("invalid MQLM_ADDR; expected host:port")
		}
		host = strings.TrimSpace(host)
		if host == "127.0.0.1" || strings.EqualFold(host, "localhost") || host == "::1" {
			cfg.AuthDisabled = true
			return cfg, nil
		}
		return Config{}, errors.New("MQLM_ADMIN_TOKEN is required unless binding to localhost")
	}

	return cfg, nil
}

func getenvDefault(key, def string) string {
	v := strings.TrimSpace(os.Getenv(key))
	if v == "" {
		return def
	}
	return v
}
