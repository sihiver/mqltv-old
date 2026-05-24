package channels

import (
	"encoding/json"
	"errors"
	"io"
	"strings"
)

// visionPlusPlaylist matches exports like v216.json (Vision+ / IndiHome TV style).
type visionPlusPlaylist struct {
	Info []visionPlusChannel `json:"info"`
}

// visionPlusChannel — semua field umum dari export Vision+ / IndiHome TV (v216.json).
type visionPlusChannel struct {
	ID             string `json:"id"`
	Name           string `json:"name"`
	Tagline        string `json:"tagline"`
	HLS            string `json:"hls"`
	Namespace      string `json:"namespace"`
	IsLive         string `json:"is_live"`
	IsMovie        string `json:"is_movie"`
	Subtitle       string `json:"subtitle"`
	Jenis          string `json:"jenis"`
	Premium        string `json:"premium"`
	Alpha2Code     string `json:"alpha_2_code"`
	CountryName    string `json:"country_name"`
	TStamp         string `json:"t_stamp"`
	SStamp         string `json:"s_stamp"`
	Logo           string `json:"logo"`
	URLLicense     string `json:"url_license"`
	FakeEvent      string `json:"fake_event"`
	HeaderIPTV     string `json:"header_iptv"`
	HeaderLicense  string `json:"header_license"`
}

// IsJSONPlaylistContent reports whether playlist body looks like JSON.
func IsJSONPlaylistContent(content string) bool {
	content = strings.TrimSpace(content)
	return strings.HasPrefix(content, "{") || strings.HasPrefix(content, "[")
}

func parsePlaylistContent(content string) ([]m3uItem, error) {
	content = strings.TrimSpace(content)
	if content == "" {
		return nil, errors.New("content is required")
	}

	if strings.HasPrefix(content, "{") || strings.HasPrefix(content, "[") {
		items, err := parseJSONPlaylist(content)
		if err != nil {
			return nil, err
		}
		if len(items) == 0 {
			return nil, errors.New("no channels found in json")
		}
		return items, nil
	}

	items, err := parseM3U(strings.NewReader(content))
	if err != nil {
		return nil, err
	}
	if len(items) == 0 {
		return nil, errors.New("no channels found in m3u")
	}
	return items, nil
}

func parseJSONPlaylist(content string) ([]m3uItem, error) {
	content = strings.TrimSpace(content)
	if content == "" {
		return nil, errors.New("content is required")
	}

	var root visionPlusPlaylist
	if err := json.Unmarshal([]byte(content), &root); err != nil {
		// Allow a bare array of channel objects.
		var arr []visionPlusChannel
		if err2 := json.Unmarshal([]byte(content), &arr); err2 != nil {
			return nil, errors.New("invalid json playlist")
		}
		root.Info = arr
	}

	if len(root.Info) == 0 {
		return nil, errors.New("no channels found in json")
	}

	out := make([]m3uItem, 0, len(root.Info))
	for _, ch := range root.Info {
		it := visionPlusToM3UItem(ch)
		if it.StreamURL == "" {
			continue
		}
		if raw, err := json.Marshal(ch); err == nil {
			it.ExtraJSON = string(raw)
		}
		out = append(out, it)
	}
	if len(out) == 0 {
		return nil, errors.New("no channels with stream url found in json")
	}
	return out, nil
}

func visionPlusToM3UItem(ch visionPlusChannel) m3uItem {
	name := strings.TrimSpace(ch.Name)
	if name == "" {
		name = strings.TrimSpace(ch.Namespace)
	}
	if name == "" {
		name = "Channel"
	}

	group := strings.TrimSpace(ch.CountryName)
	if group == "" {
		group = strings.TrimSpace(ch.Namespace)
	}
	if group == "" {
		group = strings.TrimSpace(ch.Alpha2Code)
	}

	logo := strings.TrimSpace(ch.Logo)
	if logo == " " || logo == "-" {
		logo = ""
	}

	sourceID := strings.TrimSpace(ch.ID)

	return m3uItem{
		Name:       name,
		StreamURL:  pickJSONStreamURL(ch),
		SourceID:   sourceID,
		TvgID:      sourceID,
		TvgName:    strings.TrimSpace(ch.Name),
		TvgLogo:    logo,
		GroupTitle: group,
	}
}

func pickJSONStreamURL(ch visionPlusChannel) string {
	if u := strings.TrimSpace(ch.HLS); isHTTPURL(u) {
		return u
	}
	if u := strings.TrimSpace(ch.Subtitle); isHTTPURL(u) {
		return u
	}
	return ""
}

func isHTTPURL(s string) bool {
	s = strings.ToLower(strings.TrimSpace(s))
	return strings.HasPrefix(s, "http://") || strings.HasPrefix(s, "https://")
}

// WriteM3U writes channels as an M3U playlist to w.
func WriteM3U(w io.Writer, chs []Channel) error {
	if _, err := io.WriteString(w, "#EXTM3U\n"); err != nil {
		return err
	}
	for _, c := range chs {
		name := c.Name
		if name == "" {
			name = c.TvgName
		}
		if name == "" {
			name = "Channel"
		}

		var attrs []string
		if c.TvgID != "" {
			attrs = append(attrs, `tvg-id="`+escapeM3UAttr(c.TvgID)+`"`)
		}
		if c.TvgName != "" {
			attrs = append(attrs, `tvg-name="`+escapeM3UAttr(c.TvgName)+`"`)
		}
		if c.TvgLogo != "" {
			attrs = append(attrs, `tvg-logo="`+escapeM3UAttr(c.TvgLogo)+`"`)
		}
		if c.GroupTitle != "" {
			attrs = append(attrs, `group-title="`+escapeM3UAttr(c.GroupTitle)+`"`)
		}

		meta := ""
		if len(attrs) > 0 {
			meta = " " + strings.Join(attrs, " ")
		}
		if _, err := io.WriteString(w, "#EXTINF:-1"+meta+","+name+"\n"); err != nil {
			return err
		}
		if _, err := io.WriteString(w, c.StreamURL+"\n"); err != nil {
			return err
		}
	}
	return nil
}

func escapeM3UAttr(s string) string {
	return strings.ReplaceAll(s, `"`, "")
}
