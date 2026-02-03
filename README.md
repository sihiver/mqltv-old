# MQLTV (Android TV)

Aplikasi Android TV sederhana untuk streaming IPTV dari playlist M3U.

## Fitur
- Android TV (Leanback launcher)
- List channel dari `assets/channels.m3u`
- Player pakai Media3/ExoPlayer (support HLS)

## Cara pakai
- Buka project ini di Android Studio.
- Ubah playlist di `app/src/main/assets/channels.m3u`.
- Run ke Android TV / emulator Android TV.

## mql_manager (Backend + Admin UI)

Project ini juga punya `mql_manager` (Go + SQLite) untuk import playlist, manage user, paket channel, dan generate M3U publik.

- Panduan build/run (termasuk single binary): lihat [mql_manager/README.md](mql_manager/README.md)
