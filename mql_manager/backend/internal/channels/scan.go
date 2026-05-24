package channels

const channelSelectSQL = `c.id, c.name, c.stream_url, c.tvg_id, c.tvg_name, c.tvg_logo, c.group_title, c.source_id, c.extra_json, c.created_at`

// ScanRow reads one channel row from sql.Rows or sql.Row.
func ScanRow(row interface {
	Scan(dest ...any) error
}) (Channel, error) {
	return scanChannel(row)
}

func scanChannel(row interface {
	Scan(dest ...any) error
}) (Channel, error) {
	var c Channel
	err := row.Scan(
		&c.ID, &c.Name, &c.StreamURL, &c.TvgID, &c.TvgName, &c.TvgLogo,
		&c.GroupTitle, &c.SourceID, &c.ExtraJSON, &c.CreatedAt,
	)
	return c, err
}
