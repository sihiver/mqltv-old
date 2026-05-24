package channels

import (
	"context"
	"path/filepath"
	"testing"

	"mqltv.local/mql_manager/backend/internal/db"
	"mqltv.local/mql_manager/backend/internal/migrate"
)

func TestImportM3U_DuplicateURLsNoForeignKeyError(t *testing.T) {
	t.Parallel()

	dir := t.TempDir()
	database, err := db.Open(filepath.Join(dir, "test.db"))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = database.Close() })

	ctx := context.Background()
	if err := migrate.Run(ctx, database.SQL); err != nil {
		t.Fatal(err)
	}

	res, err := database.SQL.ExecContext(ctx,
		`INSERT INTO playlists(name, source_type, source_url, content, created_at) VALUES('test', 'inline', '', '', '2020-01-01T00:00:00Z')`,
	)
	if err != nil {
		t.Fatal(err)
	}
	playlistID, err := res.LastInsertId()
	if err != nil {
		t.Fatal(err)
	}

	m3u := `#EXTM3U
#EXTINF:-1 group-title="A",Ch A
http://example.com/a
#EXTINF:-1 group-title="B",Ch B
http://example.com/b
#EXTINF:-1 group-title="A2",Ch A again
http://example.com/a
`

	repo := Repo{DB: database.SQL}
	n, err := repo.ImportM3U(ctx, playlistID, m3u)
	if err != nil {
		t.Fatalf("ImportM3U failed (FK regression?): %v", err)
	}
	if n != 3 {
		t.Fatalf("imported = %d, want 3", n)
	}

	// Re-import should also succeed (upsert + replace links).
	n, err = repo.ImportM3U(ctx, playlistID, m3u)
	if err != nil {
		t.Fatalf("re-import failed: %v", err)
	}
	if n != 3 {
		t.Fatalf("re-imported = %d, want 3", n)
	}
}
