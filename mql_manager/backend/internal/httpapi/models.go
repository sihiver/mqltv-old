package httpapi

type CreateUserRequest struct {
	Username     string                         `json:"username"`
	DisplayName  string                         `json:"displayName"`
	Password     string                         `json:"password"`
	PackageIDs   []int64                        `json:"packageIds"`
	Subscription *CreateUserSubscriptionRequest `json:"subscription"`
}

type CreateUserSubscriptionRequest struct {
	Plan      string `json:"plan"`
	ExpiresAt string `json:"expiresAt"`
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

type SetUserPackagesRequest struct {
	PackageIDs []int64 `json:"packageIds"`
}

type SetUserPasswordRequest struct {
	Password string `json:"password"`
}

type CreatePackageRequest struct {
	Name  string `json:"name"`
	Price int64  `json:"price"`
}

type SetPackageChannelsRequest struct {
	ChannelIDs []int64 `json:"channelIds"`
}
