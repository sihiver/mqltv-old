#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

DEV="${1:-192.168.15.193:5555}"

# Build debug APK (ABI split outputs). Comment out if you prefer building in Android Studio.
./gradlew :app:assembleDebug --no-daemon

APK="$ROOT_DIR/app/build/outputs/apk/debug/MQLTV-armeabi-v7a-debug.apk"
if [[ ! -f "$APK" ]]; then
	APK="$ROOT_DIR/app/build/outputs/apk/debug/app-debug.apk"
fi

echo "Installing: $APK"

# Make installs more reliable on rooted STBs (ignore if su not available)
adb -s "$DEV" shell su -c 'setenforce 0' >/dev/null 2>&1 || true

set +e
adb -s "$DEV" install -r -d -t "$APK"
RC=$?
if [[ $RC -ne 0 ]]; then
	echo "Install failed (rc=$RC). Retrying once..."
	sleep 2
	adb -s "$DEV" shell su -c 'am force-stop com.android.packageinstaller' >/dev/null 2>&1 || true
	adb -s "$DEV" shell su -c 'am force-stop com.android.defcontainer' >/dev/null 2>&1 || true
	adb -s "$DEV" install -r -d -t "$APK"
	RC=$?
fi
set -e

exit $RC