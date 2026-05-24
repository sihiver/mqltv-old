package channels

import (
	"bytes"
	"strings"
	"testing"
)

func TestChannelsHaveVisionPlusMeta(t *testing.T) {
	chs := []Channel{{
		ExtraJSON: `{"jenis":"dash-clearkey","url_license":"abc","header_iptv":"{}"}`,
	}}
	if !ChannelsHaveVisionPlusMeta(chs) {
		t.Fatal("expected vision plus meta")
	}
}

func TestWriteVisionPlusJSON_PreservesExtraFields(t *testing.T) {
	chs := []Channel{{
		SourceID:  "1",
		Name:      "Test",
		StreamURL: "http://example.com/x.mpd",
		ExtraJSON: `{"id":"1","name":"Test","hls":"http://example.com/x.mpd","jenis":"dash-clearkey","url_license":"lic","header_iptv":"{\"Referer\":\"x\"}","header_license":"{}"}`,
	}}

	var buf bytes.Buffer
	if err := WriteVisionPlusJSON(&buf, chs, "ID", "ID"); err != nil {
		t.Fatal(err)
	}
	out := buf.String()
	for _, want := range []string{"url_license", "header_iptv", "header_license", "dash-clearkey"} {
		if !strings.Contains(out, want) {
			t.Fatalf("missing %q in output: %s", want, out)
		}
	}
}
