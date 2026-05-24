package channels

import (
	"context"
	"os"
	"path/filepath"
	"strings"
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

func TestImportM3U_SavesExtraJSON(t *testing.T) {
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
		`INSERT INTO playlists(name, source_type, source_url, content, created_at) VALUES('vp', 'inline', '', '', '2020-01-01T00:00:00Z')`,
	)
	if err != nil {
		t.Fatal(err)
	}
	playlistID, _ := res.LastInsertId()

	sample := `{"info":[{"id":"99","name":"Test CH","hls":"http://example.com/x.mpd","jenis":"dash-clearkey","url_license":"abc123","header_iptv":"{}"}]}`
	repo := Repo{DB: database.SQL}
	if _, err := repo.ImportM3U(ctx, playlistID, sample); err != nil {
		t.Fatal(err)
	}

	chs, err := repo.ListChannels(ctx, &playlistID, "", 10)
	if err != nil {
		t.Fatal(err)
	}
	if len(chs) != 1 {
		t.Fatalf("len=%d want 1", len(chs))
	}
	if chs[0].SourceID != "99" {
		t.Fatalf("sourceId=%q", chs[0].SourceID)
	}
	if !strings.Contains(chs[0].ExtraJSON, "url_license") {
		t.Fatalf("extraJson missing fields: %q", chs[0].ExtraJSON)
	}
}

func TestImportM3U_VisionPlusJSON_AllEntries(t *testing.T) {
	path := "/home/dindin/Downloads/v216.json"
	b, err := os.ReadFile(path)
	if err != nil {
		t.Skip("sample file not available:", err)
	}

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
		`INSERT INTO playlists(name, source_type, source_url, content, created_at) VALUES('v216', 'inline', '', ?, '2020-01-01T00:00:00Z')`,
		string(b),
	)
	if err != nil {
		t.Fatal(err)
	}
	playlistID, _ := res.LastInsertId()

	repo := Repo{DB: database.SQL}
	n, err := repo.ImportM3U(ctx, playlistID, string(b))
	if err != nil {
		t.Fatal(err)
	}
	if n < 50 {
		t.Fatalf("imported=%d, expected ~57 channels from v216.json", n)
	}
}
