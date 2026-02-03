# mql_manager

A simple subscription/user manager for MQLTV.

- Backend: Go + SQLite (REST API)
- UI: Node.js (Express) admin panel
- Admin UI: Vue + Element Plus (Vite)

## Quick start

### 1) Run backend (Go)

```bash
cd mql_manager/backend
cp .env.example .env
# edit .env if needed

go run ./cmd/server
```

Backend defaults:
- URL: http://127.0.0.1:8080
- DB: `backend/data/mql_manager.db`

### 2) Run UI (Node)

```bash
cd mql_manager/ui
cp .env.example .env
npm install
npm run dev
```

UI defaults:
- URL: http://127.0.0.1:3000

Login with the same admin token as the backend.

Tip: the UI login accepts either plain token (`rahasia123`) or pasted header format (`Bearer rahasia123`).

### 3) (Recommended) Run Admin UI (Vue + Element Plus)

This is a nicer admin UI with a sidebar/topbar layout.

```bash
cd mql_manager/ui_admin
npm install
npm run dev
```

Admin UI defaults:
- URL: http://127.0.0.1:3001
- API proxied to backend: `/api` and `/public` → `http://127.0.0.1:8080`

## Single binary (recommended for deployment)

You can build **one executable file** that serves:

- Admin UI at `http://<host>:8080/`
- Backend API at `http://<host>:8080/api/*`
- Public endpoints at `http://<host>:8080/public/*`

### Build (on your dev machine)

Requirements (build-time only): `go` + `npm`.

```bash
cd mql_manager
./build_single.sh
```

Output:
- `mql_manager/mql_manager_server`

### Run (on target machine)

Copy just the binary (and optionally a `.env`). No Node/NPM needed.

```bash
./mql_manager_server
```

Important env vars:
- `MQLM_ADDR` (default `127.0.0.1:8080`)
- `MQLM_DB_PATH` (default `./data/mql_manager.db`)
- `MQLM_ADMIN_TOKEN`
	- If empty **and** binding to localhost, auth is disabled (dev mode)
	- If binding to non-localhost, this is required
- `MQLM_CORS_ORIGINS` (optional, comma-separated)

## API (backend)

Auth:

- Only `/api/*` endpoints require admin token (except `/api/health`).
- `/public/*`, `/playlist.m3u`, and the embedded Admin UI `/` do **not** require admin token.

For protected endpoints, send:

- `Authorization: Bearer <ADMIN_TOKEN>`

Endpoints:
- `GET /api/health`

- `GET /api/users`
- `POST /api/users`
- `GET /api/users/{id}`
- `PUT /api/users/{id}`
- `DELETE /api/users/{id}`

- `PUT /api/users/{id}/playlist` (body: `{ "playlistId": 1 }` or `{ "playlistId": null }`)

- `GET /api/users/{id}/subscriptions`
- `POST /api/users/{id}/subscriptions`
- `DELETE /api/subscriptions/{id}`

- `GET /api/playlists`
- `POST /api/playlists` (JSON import URL: `{ "name": "Demo", "url": "https://..." }`)
- `POST /api/playlists` (multipart upload: fields `name`, `file`)
- `DELETE /api/playlists/{id}`
- `POST /api/playlists/{id}/reimport`

- `GET /api/channels`
- `GET /api/users/{id}/channels`
- `PUT /api/users/{id}/channels`

- `GET /api/packages`
- `POST /api/packages`
- `GET /api/packages/{id}`
- `DELETE /api/packages/{id}`
- `GET /api/packages/{id}/channels`
- `PUT /api/packages/{id}/channels`

- `GET /api/users/{id}/packages`
- `PUT /api/users/{id}/packages`

Public (no admin token):
- `POST /public/login`
- `GET /public/m3u/{playlistId}.m3u`
- `GET /public/users/{appKey}/playlist.m3u`

## Notes

- This is a minimal scaffold intended to be extended (device binding, license keys, plans, audit logs, etc.).
