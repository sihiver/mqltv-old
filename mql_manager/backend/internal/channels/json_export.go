package channels

import (
	"encoding/json"
	"io"
	"strings"
)

// visionPlusExport is the root object when exporting a playlist as JSON.
type visionPlusExport struct {
	CountryName string              `json:"country_name,omitempty"`
	Country     string              `json:"country,omitempty"`
	Info        []visionPlusChannel `json:"info"`
}

// ChannelsHaveVisionPlusMeta reports whether channels carry Vision+ JSON metadata
// (url_license, header_iptv, jenis, etc.) in extra_json.
func ChannelsHaveVisionPlusMeta(chs []Channel) bool {
	for _, c := range chs {
		raw := strings.TrimSpace(c.ExtraJSON)
		if raw == "" || !strings.HasPrefix(raw, "{") {
			continue
		}
		if strings.Contains(raw, "url_license") ||
			strings.Contains(raw, "header_iptv") ||
			strings.Contains(raw, "header_license") ||
			strings.Contains(raw, "jenis") {
			return true
		}
	}
	return false
}

// WriteVisionPlusJSON rebuilds a Vision+-style playlist from stored channels.
func WriteVisionPlusJSON(w io.Writer, chs []Channel, countryName, country string) error {
	info := make([]visionPlusChannel, 0, len(chs))
	for _, c := range chs {
		if raw := strings.TrimSpace(c.ExtraJSON); raw != "" && strings.HasPrefix(raw, "{") {
			var ch visionPlusChannel
			if err := json.Unmarshal([]byte(raw), &ch); err == nil {
				info = append(info, ch)
				continue
			}
		}
		fallback := visionPlusChannel{
			ID:          c.SourceID,
			Name:        c.Name,
			HLS:         c.StreamURL,
			CountryName: c.GroupTitle,
			Logo:        c.TvgLogo,
		}
		if j := inferJenisFromStreamURL(c.StreamURL); j != "" {
			fallback.Jenis = j
		}
		info = append(info, fallback)
	}
	root := visionPlusExport{
		CountryName: countryName,
		Country:     country,
		Info:        info,
	}
	enc := json.NewEncoder(w)
	enc.SetIndent("", "  ")
	return enc.Encode(root)
}

func inferJenisFromStreamURL(url string) string {
	u := strings.ToLower(strings.TrimSpace(url))
	switch {
	case strings.Contains(u, ".m3u8"), strings.Contains(u, "application/vnd.apple.mpegurl"):
		return "hls"
	case strings.Contains(u, ".mpd"), strings.Contains(u, "dash"):
		return "dash"
	case strings.Contains(u, ".mp4"), strings.Contains(u, ".ts"):
		return "progressive"
	default:
		return ""
	}
}
