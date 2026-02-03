package channels

import (
	"strings"
	"testing"
)

func TestParseExtInf_InlineURLAndAttributes(t *testing.T) {
	line := `#EXTINF:-1 tvg-id="UseePrime.id" tvg-chno="107" tvg-logo="https://images.indihometv.com/images/channels/image_ch_sctv.png" group-title="Local",SCTV HD                    http://192.168.15.1:5140/Local/SCTV%20HD`

	it := parseExtInf(line)

	if it.Name != "SCTV HD" {
		t.Fatalf("name = %q", it.Name)
	}
	if it.StreamURL != "http://192.168.15.1:5140/Local/SCTV%20HD" {
		t.Fatalf("streamURL = %q", it.StreamURL)
	}
	if it.GroupTitle != "Local" {
		t.Fatalf("groupTitle = %q", it.GroupTitle)
	}
	if it.TvgLogo != "https://images.indihometv.com/images/channels/image_ch_sctv.png" {
		t.Fatalf("tvgLogo = %q", it.TvgLogo)
	}
	if it.TvgID != "UseePrime.id" {
		// help debug attribute parsing failures
		attrPart := line
		attrPart = strings.TrimSpace(strings.TrimPrefix(attrPart, "#EXTINF:"))
		if idx := strings.Index(attrPart, ","); idx >= 0 {
			attrPart = strings.TrimSpace(attrPart[:idx])
		}
		t.Logf("attrPart=%q", attrPart)
		t.Logf("attrs=%v", parseAttributes(attrPart))
		t.Fatalf("tvgID = %q", it.TvgID)
	}
}
