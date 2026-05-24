package channels

import (
	"os"
	"testing"
)

func TestParseJSONPlaylist_VisionPlusSample(t *testing.T) {
	t.Parallel()

	sample := `{
  "country_name": "Indonesia",
  "country": "ID",
  "info": [
    {
      "id": "2830",
      "name": "ANTV HD",
      "hls": "https://example.com/antv/index.mpd",
      "namespace": "antv hd",
      "country_name": "Indonesia",
      "logo": "https://images.example.com/antv.png"
    },
    {
      "id": "9999",
      "name": "No URL",
      "hls": "",
      "country_name": "Skip"
    }
  ]
}`

	items, err := parseJSONPlaylist(sample)
	if err != nil {
		t.Fatal(err)
	}
	if len(items) != 1 {
		t.Fatalf("len(items)=%d want 1", len(items))
	}
	if items[0].Name != "ANTV HD" {
		t.Fatalf("name=%q", items[0].Name)
	}
	if items[0].StreamURL != "https://example.com/antv/index.mpd" {
		t.Fatalf("streamURL=%q", items[0].StreamURL)
	}
	if items[0].GroupTitle != "Indonesia" {
		t.Fatalf("groupTitle=%q", items[0].GroupTitle)
	}
	if items[0].TvgID != "2830" {
		t.Fatalf("tvgID=%q", items[0].TvgID)
	}
}

func TestParsePlaylistContent_DetectsM3U(t *testing.T) {
	t.Parallel()

	m3u := "#EXTM3U\n#EXTINF:-1,Test\nhttp://example.com/live\n"
	items, err := parsePlaylistContent(m3u)
	if err != nil {
		t.Fatal(err)
	}
	if len(items) != 1 || items[0].Name != "Test" {
		t.Fatalf("unexpected items: %+v", items)
	}
}

func TestImportM3U_FromVisionPlusJSONFile(t *testing.T) {
	path := "/home/dindin/Downloads/v216.json"
	b, err := os.ReadFile(path)
	if err != nil {
		t.Skip("sample file not available:", err)
	}

	items, err := parseJSONPlaylist(string(b))
	if err != nil {
		t.Fatal(err)
	}
	if len(items) < 10 {
		t.Fatalf("expected many channels, got %d", len(items))
	}
}
