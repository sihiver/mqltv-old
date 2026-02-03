import { auth } from './auth'

export type User = {
  id: number
  username: string
  displayName: string
  appKey?: string
  playlistId?: number | null
  createdAt: string
  packages?: string[]
  expiresAt?: string
}

export type Playlist = {
  id: number
  name: string
  sourceType: 'url' | 'inline'
  sourceUrl: string
  createdAt: string
  publicUrl: string
}

export type Subscription = {
  id: number
  userId: number
  plan: string
  expiresAt: string
  createdAt: string
}

export type Channel = {
  id: number
  name: string
  streamUrl: string
  tvgId: string
  tvgName: string
  tvgLogo: string
  groupTitle: string
  createdAt: string
}

export type Package = {
  id: number
  name: string
  price: number
  createdAt: string
}

async function request<T>(path: string, init: RequestInit = {}): Promise<T> {
  const headers = new Headers(init.headers || {})
  headers.set('Accept', 'application/json')

  const token = auth.getToken()
  if (token) {
    headers.set('Authorization', `Bearer ${token}`)
  }
  const isFormData = typeof FormData !== 'undefined' && init.body instanceof FormData
  if (init.body && !isFormData && !headers.has('Content-Type')) {
    headers.set('Content-Type', 'application/json')
  }

  const resp = await fetch(path, { ...init, headers })
  const contentType = resp.headers.get('content-type') || ''
  const isJson = contentType.includes('application/json')
  const data: any = isJson ? await resp.json() : await resp.text()

  if (!resp.ok) {
    const message = typeof data === 'string' ? data : data?.error || 'API error'
    throw new Error(message)
  }
  return data as T
}

export const api = {
  health() {
    return request<{ ok: boolean; time: string; authRequired?: boolean }>('/api/health')
  },
  listUsers() {
    return request<User[]>('/api/users')
  },
  createUser(payload: {
    username: string
    displayName: string
    password?: string
    packageIds?: number[]
    subscription?: { plan: string; expiresAt: string }
  }) {
    return request<User>('/api/users', { method: 'POST', body: JSON.stringify(payload) })
  },

  getUser(id: number) {
    return request<User>(`/api/users/${id}`)
  },
  deleteUser(id: number) {
    return request<void>(`/api/users/${id}`, { method: 'DELETE' })
  },
  listSubscriptions(userId: number) {
    return request<Subscription[]>(`/api/users/${userId}/subscriptions`)
  },
  createSubscription(userId: number, payload: { plan: string; expiresAt: string }) {
    return request<Subscription>(`/api/users/${userId}/subscriptions`, {
      method: 'POST',
      body: JSON.stringify(payload)
    })
  },
  deleteSubscription(id: number) {
    return request<void>(`/api/subscriptions/${id}`, { method: 'DELETE' })
  },

  listPlaylists() {
    return request<Playlist[]>('/api/playlists')
  },
  createPlaylistFromURL(payload: { name: string; url: string }) {
    return request<Playlist>('/api/playlists', { method: 'POST', body: JSON.stringify(payload) })
  },
  uploadPlaylist(payload: { name: string; file: File }) {
    const fd = new FormData()
    fd.set('name', payload.name)
    fd.set('file', payload.file)
    return request<Playlist>('/api/playlists', { method: 'POST', body: fd })
  },
  deletePlaylist(id: number) {
    return request<void>(`/api/playlists/${id}`, { method: 'DELETE' })
  },
  reimportPlaylist(id: number) {
    return request<{ ok: boolean; imported: number }>(`/api/playlists/${id}/reimport`, { method: 'POST' })
  },
  setUserPlaylist(userId: number, playlistId: number | null) {
    return request<User>(`/api/users/${userId}/playlist`, {
      method: 'PUT',
      body: JSON.stringify({ playlistId })
    })
  },

  listChannels(params?: { playlistId?: number | null; q?: string; limit?: number }) {
    const sp = new URLSearchParams()
    if (params?.playlistId != null) sp.set('playlistId', String(params.playlistId))
    if (params?.q) sp.set('q', params.q)
    if (params?.limit) sp.set('limit', String(params.limit))
    const qs = sp.toString()
    return request<Channel[]>(`/api/channels${qs ? `?${qs}` : ''}`)
  },

  getUserChannels(userId: number) {
    return request<Channel[]>(`/api/users/${userId}/channels`)
  },

  setUserChannels(userId: number, channelIds: number[]) {
    return request<Channel[]>(`/api/users/${userId}/channels`, {
      method: 'PUT',
      body: JSON.stringify({ channelIds })
    })
  },

  getUserPackages(userId: number) {
    return request<Package[]>(`/api/users/${userId}/packages`)
  },

  setUserPackages(userId: number, packageIds: number[]) {
    return request<Package[]>(`/api/users/${userId}/packages`, {
      method: 'PUT',
      body: JSON.stringify({ packageIds })
    })
  },

  listPackages() {
    return request<Package[]>('/api/packages')
  },

  createPackage(payload: { name: string; price: number }) {
    return request<Package>('/api/packages', { method: 'POST', body: JSON.stringify(payload) })
  },

  getPackage(id: number) {
    return request<Package>(`/api/packages/${id}`)
  },

  deletePackage(id: number) {
    return request<void>(`/api/packages/${id}`, { method: 'DELETE' })
  },

  getPackageChannels(packageId: number) {
    return request<Channel[]>(`/api/packages/${packageId}/channels`)
  },

  setPackageChannels(packageId: number, channelIds: number[]) {
    return request<Channel[]>(`/api/packages/${packageId}/channels`, {
      method: 'PUT',
      body: JSON.stringify({ channelIds })
    })
  }
}
