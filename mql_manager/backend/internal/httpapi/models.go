package httpapi

type CreateUserRequest struct {
	Username    string `json:"username"`
	DisplayName string `json:"displayName"`
	Password    string `json:"password"`
}

type UpdateUserRequest struct {
	Username    *string `json:"username"`
	DisplayName *string `json:"displayName"`
}

type CreateSubscriptionRequest struct {
	Plan      string `json:"plan"`
	ExpiresAt string `json:"expiresAt"`
}

type CreatePlaylistFromURLRequest struct {
	Name string `json:"name"`
	URL  string `json:"url"`
}

type SetUserPlaylistRequest struct {
	PlaylistID *int64 `json:"playlistId"`
}

type SetUserChannelsRequest struct {
	ChannelIDs []int64 `json:"channelIds"`
}

type SetUserPasswordRequest struct {
	Password string `json:"password"`
}
