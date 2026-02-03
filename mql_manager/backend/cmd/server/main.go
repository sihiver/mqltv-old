package main

import (
	"context"
	"log"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	"github.com/joho/godotenv"
	"mqltv.local/mql_manager/backend/internal/auth"
	"mqltv.local/mql_manager/backend/internal/channels"
	"mqltv.local/mql_manager/backend/internal/config"
	"mqltv.local/mql_manager/backend/internal/db"
	"mqltv.local/mql_manager/backend/internal/httpapi"
	"mqltv.local/mql_manager/backend/internal/migrate"
	"mqltv.local/mql_manager/backend/internal/packages"
	"mqltv.local/mql_manager/backend/internal/playlists"
	"mqltv.local/mql_manager/backend/internal/users"
	"mqltv.local/mql_manager/backend/internal/util"
)

func main() {
	_ = godotenv.Load()

	cfg, err := config.Load()
	if err != nil {
		log.Fatal(err)
	}

	database, err := db.Open(cfg.DBPath)
	if err != nil {
		log.Fatal(err)
	}
	defer func() { _ = database.Close() }()

	if err := migrate.Run(context.Background(), database.SQL); err != nil {
		log.Fatal(err)
	}

	mux := http.NewServeMux()
	api := httpapi.API{
		Users:        users.Repo{DB: database.SQL},
		Playlists:    playlists.Repo{DB: database.SQL},
		Channels:     channels.Repo{DB: database.SQL},
		Packages:     packages.Repo{DB: database.SQL},
		AuthRequired: !cfg.AuthDisabled,
	}
	api.Register(mux)

	h := util.Logging(mux)
	h = httpapi.CORS{AllowedOrigins: cfg.CORSOrigins}.Middleware(h)
	h = auth.TokenAuth{AdminToken: cfg.AdminToken, Disabled: cfg.AuthDisabled}.Middleware(h)

	srv := &http.Server{
		Addr:              cfg.Addr,
		Handler:           h,
		ReadHeaderTimeout: 5 * time.Second,
	}

	go func() {
		log.Printf("mql_manager backend listening on http://%s", cfg.Addr)
		if err := srv.ListenAndServe(); err != nil && err != http.ErrServerClosed {
			log.Fatal(err)
		}
	}()

	stop := make(chan os.Signal, 1)
	signal.Notify(stop, syscall.SIGINT, syscall.SIGTERM)
	<-stop

	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	_ = srv.Shutdown(ctx)
	log.Printf("shutdown complete")
}
