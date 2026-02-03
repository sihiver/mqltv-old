#!/usr/bin/env bash
set -euo pipefail

root_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

echo "[1/3] Building admin UI (ui_admin)..."
cd "$root_dir/ui_admin"
npm run build

echo "[2/3] Copying dist -> backend/internal/webui/dist..."
mkdir -p "$root_dir/backend/internal/webui/dist"
if command -v rsync >/dev/null 2>&1; then
	rsync -a --delete "$root_dir/ui_admin/dist/" "$root_dir/backend/internal/webui/dist/"
else
	rm -rf "$root_dir/backend/internal/webui/dist"/*
	cp -a "$root_dir/ui_admin/dist"/. "$root_dir/backend/internal/webui/dist/"
fi

echo "[3/3] Building single Go binary..."
cd "$root_dir/backend"
go build -trimpath -ldflags "-s -w" -o "$root_dir/mql_manager_server" ./cmd/server

echo "Done: $root_dir/mql_manager_server"
echo "Run:  ./mql_manager_server"
