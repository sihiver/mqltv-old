#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

DEFAULT_DEV="192.168.15.193:5555"
DEV="$DEFAULT_DEV"
MODE="app" # app | vidio
VARIANT="" # debug | release (default depends on mode)
BUILD=1
LAUNCH=0
CHECK_XWALK=0
APK_OVERRIDE=""

usage() {
	cat <<EOF
Usage:
	$0 [deviceSerial] [--app|--vidio] [--debug|--release] [--apk PATH] [--no-build] [--launch] [--check-xwalk]

Examples:
	$0                      # push :app debug to $DEFAULT_DEV
	$0 192.168.15.193:5555  # push :app debug to STB
	$0 --vidio --launch      # install tmp/vidio-universal.apk and launch
	$0 --vidio --debug        # build & install com.sihiver.vidio/build/outputs/apk/debug/vidio-debug.apk
	$0 --apk tmp/vidio-universal.apk

Notes:
	- If 'adb install' fails on some rooted STBs, this script falls back to 'su pm install'.
EOF
}

if [[ $# -gt 0 && "${1:-}" != "" && "${1:0:1}" != "-" ]]; then
	DEV="$1"
	shift
fi

while [[ $# -gt 0 ]]; do
	case "$1" in
		--device)
			if [[ $# -lt 2 || -z "${2:-}" || "${2:0:1}" == "-" ]]; then
				echo "Error: --device membutuhkan nilai, contoh: --device 192.168.1.10:5555" >&2
				usage >&2
				exit 2
			fi
			DEV="${2:-}"
			shift 2
			;;
		--app)
			MODE="app"
			shift
			;;
		--vidio)
			MODE="vidio"
			shift
			;;
		--debug)
			VARIANT="debug"
			shift
			;;
		--release)
			VARIANT="release"
			shift
			;;
		--apk)
			APK_OVERRIDE="${2:-}"
			shift 2
			;;
		--no-build)
			BUILD=0
			shift
			;;
		--launch)
			LAUNCH=1
			shift
			;;
		--check-xwalk)
			CHECK_XWALK=1
			shift
			;;
		-h|--help)
			usage
			exit 0
			;;
		*)
			echo "Unknown arg: $1" >&2
			usage >&2
			exit 2
			;;
	esac
done

if [[ -z "$DEV" ]]; then
	DEV="$DEFAULT_DEV"
fi

get_device_abi() {
	adb -s "$DEV" shell getprop ro.product.cpu.abi 2>/dev/null | tr -d '\r' | head -n 1
}

normalize_variant() {
	if [[ -z "$VARIANT" ]]; then
		if [[ "$MODE" == "vidio" ]]; then
			VARIANT="release"
		else
			VARIANT="debug"
		fi
	fi
}

pick_app_apk() {
	local abi
	abi="$(get_device_abi || true)"
	local candidate=""
	if [[ -n "$abi" ]]; then
		candidate="$ROOT_DIR/app/build/outputs/apk/debug/MQLTV-${abi}-debug.apk"
		if [[ -f "$candidate" ]]; then
			echo "$candidate"
			return 0
		fi
	fi
	if [[ -f "$ROOT_DIR/app/build/outputs/apk/debug/app-debug.apk" ]]; then
		echo "$ROOT_DIR/app/build/outputs/apk/debug/app-debug.apk"
		return 0
	fi
	return 1
}

pick_vidio_apk() {
	normalize_variant
	if [[ "$VARIANT" == "debug" ]]; then
		if [[ -f "$ROOT_DIR/com.sihiver.vidio/build/outputs/apk/debug/vidio-debug.apk" ]]; then
			echo "$ROOT_DIR/com.sihiver.vidio/build/outputs/apk/debug/vidio-debug.apk"
			return 0
		fi
		return 1
	fi

	# release: Prefer the universal release APK generated from bundletool.
	if [[ -f "$ROOT_DIR/tmp/vidio-universal.apk" ]]; then
		echo "$ROOT_DIR/tmp/vidio-universal.apk"
		return 0
	fi
	return 1
}

ensure_apk() {
	local apk=""
	if [[ -n "$APK_OVERRIDE" ]]; then
		apk="$APK_OVERRIDE"
	else
		if [[ "$MODE" == "vidio" ]]; then
			apk="$(pick_vidio_apk)" || true
		else
			apk="$(pick_app_apk)" || true
		fi
	fi

	if [[ -z "$apk" || ! -f "$apk" ]]; then
		echo "APK not found: $apk" >&2
		exit 3
	fi

	echo "$apk"
}

build_if_needed() {
	normalize_variant
	if [[ "$BUILD" -eq 0 ]]; then
		return 0
	fi

	if [[ -n "$APK_OVERRIDE" ]]; then
		# If user provided a custom APK path, don't rebuild.
		return 0
	fi

	if [[ "$MODE" == "vidio" ]]; then
		if [[ "$VARIANT" == "debug" ]]; then
			./gradlew :vidio:assembleDebug --no-daemon
			return 0
		fi
		# release: Universal APK is already generated in tmp/. If missing, let ensure_apk fail with a clear message.
		return 0
	fi

	# Build debug APK (ABI split outputs). Comment out if you prefer building in Android Studio.
	./gradlew :app:assembleDebug --no-daemon
}

check_xwalk_pkgs() {
	echo "Checking Crosswalk runtime packages on device: $DEV"
	# Avoid listing ALL packages (can crash PackageManager IPC on some STBs).
	adb -s "$DEV" shell su -c 'pm list packages org.xwalk.core || true' 2>/dev/null || \
		adb -s "$DEV" shell 'pm list packages org.xwalk.core || true'
	adb -s "$DEV" shell su -c 'pm path org.xwalk.core || true' 2>/dev/null || \
		adb -s "$DEV" shell 'pm path org.xwalk.core || true'
}

install_apk() {
	local apk="$1"
	local tmp_on_device="/data/local/tmp/pushapp.apk"

	echo "Installing to $DEV: $apk"

	# Make installs more reliable on rooted STBs (ignore if su not available)
	adb -s "$DEV" shell su -c 'setenforce 0' >/dev/null 2>&1 || true

	set +e
	adb -s "$DEV" install -r -d -t "$apk"
	local rc=$?
	set -e

	if [[ $rc -eq 0 ]]; then
		return 0
	fi

	echo "adb install failed (rc=$rc). Falling back to rooted install via su + pm..."
	adb -s "$DEV" push "$apk" "$tmp_on_device" >/dev/null
	set +e
	adb -s "$DEV" shell su -c "pm install -r -d -t '$tmp_on_device'"
	rc=$?
	set -e
	adb -s "$DEV" shell su -c "rm -f '$tmp_on_device'" >/dev/null 2>&1 || true
	return $rc
}

launch_app() {
	local component=""
	if [[ "$MODE" == "vidio" ]]; then
		component="com.sihiver.vidio/.MainActivity"
	else
		component="com.mqltv/.MainActivity"
	fi
	echo "Launching: $component"
	adb -s "$DEV" shell am start -n "$component" >/dev/null
}

build_if_needed

if [[ "$CHECK_XWALK" -eq 1 ]]; then
	check_xwalk_pkgs
fi

APK="$(ensure_apk)"
install_apk "$APK"

if [[ "$LAUNCH" -eq 1 ]]; then
	launch_app
fi

echo "Done."