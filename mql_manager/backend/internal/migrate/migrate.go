package migrate

import (
	"context"
	"database/sql"
	"embed"
	"fmt"
	"sort"
	"strings"
	"time"
)

//go:embed migrations/*.sql
var migrationsFS embed.FS

type Migration struct {
	Name string
	SQL  string
}

func Run(ctx context.Context, db *sql.DB) error {
	ctx, cancel := context.WithTimeout(ctx, 20*time.Second)
	defer cancel()

	if _, err := db.ExecContext(ctx, `CREATE TABLE IF NOT EXISTS schema_migrations (name TEXT PRIMARY KEY, applied_at TEXT NOT NULL);`); err != nil {
		return err
	}

	names, err := migrationsFS.ReadDir("migrations")
	if err != nil {
		return err
	}

	var migNames []string
	for _, e := range names {
		if e.IsDir() {
			continue
		}
		if strings.HasSuffix(e.Name(), ".sql") {
			migNames = append(migNames, e.Name())
		}
	}
	sort.Strings(migNames)

	for _, name := range migNames {
		applied, err := isApplied(ctx, db, name)
		if err != nil {
			return err
		}
		if applied {
			continue
		}

		b, err := migrationsFS.ReadFile("migrations/" + name)
		if err != nil {
			return err
		}

		tx, err := db.BeginTx(ctx, nil)
		if err != nil {
			return err
		}

		if _, err := tx.ExecContext(ctx, string(b)); err != nil {
			_ = tx.Rollback()
			return fmt.Errorf("migration %s failed: %w", name, err)
		}
		if _, err := tx.ExecContext(ctx, `INSERT INTO schema_migrations(name, applied_at) VALUES(?, datetime('now'));`, name); err != nil {
			_ = tx.Rollback()
			return err
		}
		if err := tx.Commit(); err != nil {
			return err
		}
	}

	return nil
}

func isApplied(ctx context.Context, db *sql.DB, name string) (bool, error) {
	var v string
	err := db.QueryRowContext(ctx, `SELECT name FROM schema_migrations WHERE name = ?`, name).Scan(&v)
	if err == sql.ErrNoRows {
		return false, nil
	}
	if err != nil {
		return false, err
	}
	return true, nil
}
