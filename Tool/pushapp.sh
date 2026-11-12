#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

DEFAULT_IP="${STB_IP:-192.168.1.76}"
DEFAULT_PORT="5555"
DEFAULT_DEV="${DEFAULT_IP}:${DEFAULT_PORT}"

DEV=""
MODE="app" # app | vidio
VARIANT="" # debug | release | auto
BUILD=1
LAUNCH=0
CHECK_XWALK=0
APK_OVERRIDE=""
INSTALL_TO_SYSTEM=0
ENABLE_TCPIP=0
FORCE_USB=0

usage() {
	cat <<EOF
Usage:
	$0 [deviceSerial|IP] [options]

Targeting:
	[deviceSerial|IP]       Device serial atau IP target (default: $DEFAULT_DEV atau USB jika terpasang)
	--device SERIAL|IP      Targetkan device serial atau IP tertentu
	--ip IP                 Targetkan STB berdasarkan IP (port otomatis 5555)
	--usb                   Paksa gunakan perangkat yang terhubung via USB
	--tcpip                 Aktifkan mode wireless debugging (port 5555) pada STB yang terhubung USB

App & Build:
	--app                   Target aplikasi :app (MQLTV) [Default]
	--vidio                 Target aplikasi :vidio
	--debug                 Gunakan / build varian debug
	--release               Gunakan / build varian release (contoh: app/release/MQLTV-*-release.apk)
	--apk PATH              Install APK manual dari path tertentu
	--no-build              Jangan build gradle, langsung pasang APK yang ada
	--system                Pasang langsung ke /system/priv-app/MQLTV.apk (butuh root di STB)
	--launch                Otomatis jalankan aplikasi setelah instalasi selesai
	--check-xwalk           Periksa instalasi paket runtime Crosswalk pada STB
	-h, --help              Tampilkan bantuan ini

Contoh:
	$0                      # Pasang MQLTV ke STB aktif ($DEFAULT_DEV atau USB)
	$0 192.168.1.76         # Pasang ke STB pada IP 192.168.1.76:5555 (otomatis connect)
	$0 --release --no-build # Pasang APK release yang sudah ada di app/release/
	$0 --usb --tcpip        # STB tercolok USB -> aktifkan adb tcpip 5555 untuk Wi-Fi
	$0 --launch             # Pasang dan langsung jalankan di layar TV
EOF
}

# Parse positional argument (if first arg is not an option)
if [[ $# -gt 0 && "${1:-}" != "" && "${1:0:1}" != "-" ]]; then
	DEV="$1"
	shift
fi

while [[ $# -gt 0 ]]; do
	case "$1" in
		--device)
			if [[ $# -lt 2 || -z "${2:-}" || "${2:0:1}" == "-" ]]; then
				echo "Error: --device membutuhkan serial atau IP" >&2
				usage >&2
				exit 2
			fi
			DEV="${2:-}"
			shift 2
			;;
		--ip)
			if [[ $# -lt 2 || -z "${2:-}" || "${2:0:1}" == "-" ]]; then
				echo "Error: --ip membutuhkan alamat IP, contoh: --ip 192.168.1.76" >&2
				usage >&2
				exit 2
			fi
			DEV="${2:-}:${DEFAULT_PORT}"
			shift 2
			;;
		--usb)
			FORCE_USB=1
			shift
			;;
		--tcpip)
			ENABLE_TCPIP=1
			shift
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
		--system)
			INSTALL_TO_SYSTEM=1
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

ensure_connected() {
	local connected_devs=($(adb devices 2>/dev/null | awk 'NR>1 && $2=="device" {print $1}' || true))
	local usb_devs=($(adb devices 2>/dev/null | awk 'NR>1 && $2=="device" && $1 !~ /:[0-9]+/ {print $1}' || true))

	if [[ "$FORCE_USB" -eq 1 ]]; then
		if [[ ${#usb_devs[@]} -eq 0 ]]; then
			echo "Error: Tidak ada perangkat USB yang terdeteksi via adb." >&2
			echo "Periksa kabel USB ke STB (gunakan port OTG / USB 2 pada B760H)." >&2
			exit 1
		fi
		DEV="${usb_devs[0]}"
		echo "Menggunakan perangkat USB: $DEV"
		return 0
	fi

	# Format DEV if specified as plain IP without port
	if [[ -n "$DEV" && "$DEV" =~ ^[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
		DEV="${DEV}:${DEFAULT_PORT}"
	fi

	# If no specific device requested:
	if [[ -z "$DEV" ]]; then
		if [[ ${#usb_devs[@]} -gt 0 ]]; then
			DEV="${usb_devs[0]}"
			echo "Terdeteksi STB via USB: $DEV"
		elif [[ ${#connected_devs[@]} -gt 0 ]]; then
			DEV="${connected_devs[0]}"
			echo "Menggunakan perangkat terhubung: $DEV"
		else
			DEV="$DEFAULT_DEV"
		fi
	fi

	# If DEV is a network IP address, attempt connection if not yet connected
	if [[ "$DEV" =~ :[0-9]+$ ]]; then
		local is_connected=0
		for d in "${connected_devs[@]:-}"; do
			if [[ "$d" == "$DEV" ]]; then
				is_connected=1
				break
			fi
		done

		if [[ $is_connected -eq 0 ]]; then
			echo "Menghubungkan ke STB via jaringan: $DEV (timeout 4s)..."
			if timeout 4 adb connect "$DEV" 2>/dev/null; then
				sleep 0.5
			else
				echo "Peringatan: adb connect timeout." >&2
			fi
		fi
	fi

	# Check device state in adb
	local state
	state="$(adb devices 2>/dev/null | awk -v d="$DEV" '$1==d {print $2}')"

	if [[ "$state" != "device" ]]; then
		# If network failed, check if a USB device is available as a fallback
		if [[ ${#usb_devs[@]} -gt 0 ]]; then
			echo "Perangkat $DEV tidak dapat dijangkau, namun ada USB STB: ${usb_devs[0]}"
			echo "Beralih otomatis ke perangkat USB: ${usb_devs[0]}"
			DEV="${usb_devs[0]}"
			return 0
		fi

		if [[ "$state" == "unauthorized" ]]; then
			echo "Error: Perangkat $DEV berstatus 'unauthorized'!" >&2
			echo "Solusi: Di KitKat, firmware baru sudah dimodifikasi ro.adb.secure=0 agar tidak perlu otorisasi." >&2
			echo "Jika masih unauthorized, cabut dan pasang kembali kabel USB, atau restart adb: 'adb kill-server && adb start-server'." >&2
		else
			echo "Error: Perangkat $DEV tidak terhubung (status: ${state:-tidak ditemukan})." >&2
			echo "Daftar perangkat adb saat ini:" >&2
			adb devices >&2
			echo "" >&2
			echo "Tips koneksi STB KitKat B760H:" >&2
			echo "1. Wi-Fi / LAN: Pastikan STB satu jaringan dan ketik: $0 <IP_STB>" >&2
			echo "2. USB: Hubungkan kabel USB male-to-male ke port USB STB (port 2)." >&2
		fi
		exit 1
	fi
}

setup_tcpip_if_requested() {
	if [[ "$ENABLE_TCPIP" -eq 1 ]]; then
		echo "Mengaktifkan port TCP 5555 pada $DEV..."
		adb -s "$DEV" tcpip 5555
		sleep 1
		# Try to fetch device IP address
		local ip
		ip="$(adb -s "$DEV" shell "ip route get 1.1.1.1 2>/dev/null | awk '{print \$NF; exit}'" 2>/dev/null | tr -d '\r\n' || true)"
		if [[ -z "$ip" ]]; then
			ip="$(adb -s "$DEV" shell "getprop dhcp.wlan0.ipaddress" 2>/dev/null | tr -d '\r\n' || true)"
		fi
		if [[ -z "$ip" ]]; then
			ip="$(adb -s "$DEV" shell "getprop dhcp.eth0.ipaddress" 2>/dev/null | tr -d '\r\n' || true)"
		fi
		echo "Mode wireless ADB aktif!"
		if [[ -n "$ip" ]]; then
			echo "IP STB terdeteksi: $ip"
			echo "Anda sekarang dapat mencabut kabel USB dan menghubungkan via: adb connect $ip:5555"
		fi
	fi
}

get_device_abi() {
	local abi
	abi="$(adb -s "$DEV" shell getprop ro.product.cpu.abi 2>/dev/null | tr -d '\r\n' || true)"
	if [[ -z "$abi" ]]; then
		abi="armeabi-v7a"
	fi
	echo "$abi"
}

get_device_sdk() {
	local sdk
	sdk="$(adb -s "$DEV" shell getprop ro.build.version.sdk 2>/dev/null | tr -d '\r\n' || true)"
	if [[ -z "$sdk" ]]; then
		sdk="19" # Default fallback to KitKat (API 19) for B760H
	fi
	echo "$sdk"
}

normalize_variant() {
	if [[ -z "$VARIANT" ]]; then
		if [[ "$MODE" == "vidio" ]]; then
			VARIANT="release"
		else
			# For app: if debug apk exists, use debug, otherwise check release
			local abi
			abi="$(get_device_abi)"
			if [[ -f "$ROOT_DIR/app/build/outputs/apk/debug/MQLTV-${abi}-debug.apk" || -f "$ROOT_DIR/app/build/outputs/apk/debug/app-debug.apk" ]]; then
				VARIANT="debug"
			elif [[ -f "$ROOT_DIR/app/release/MQLTV-${abi}-release.apk" || -f "$ROOT_DIR/app/release/MQLTV-armeabi-v7a-release.apk" ]]; then
				VARIANT="release"
			else
				VARIANT="debug"
			fi
		fi
	fi
}

pick_app_apk() {
	local abi
	abi="$(get_device_abi)"
	normalize_variant

	local candidates=()
	if [[ "$VARIANT" == "release" ]]; then
		candidates+=(
			"$ROOT_DIR/app/release/MQLTV-${abi}-release.apk"
			"$ROOT_DIR/app/release/MQLTV-armeabi-v7a-release.apk"
			"$ROOT_DIR/app/build/outputs/apk/release/MQLTV-${abi}-release.apk"
			"$ROOT_DIR/app/build/outputs/apk/release/app-release.apk"
		)
	else
		candidates+=(
			"$ROOT_DIR/app/build/outputs/apk/debug/MQLTV-${abi}-debug.apk"
			"$ROOT_DIR/app/build/outputs/apk/debug/app-debug.apk"
			"$ROOT_DIR/app/release/MQLTV-${abi}-release.apk"
			"$ROOT_DIR/app/release/MQLTV-armeabi-v7a-release.apk"
		)
	fi

	for c in "${candidates[@]}"; do
		if [[ -f "$c" ]]; then
			echo "$c"
			return 0
		fi
	done

	# Fallback wildcard search
	local fallback=""
	if [[ "$VARIANT" == "release" ]]; then
		fallback="$(find "$ROOT_DIR/app/release" "$ROOT_DIR/app/build/outputs/apk/release" -maxdepth 1 -name "MQLTV-*-release.apk" 2>/dev/null | head -n 1 || true)"
	else
		fallback="$(find "$ROOT_DIR/app/build/outputs/apk/debug" -maxdepth 1 -name "MQLTV-*-debug.apk" 2>/dev/null | head -n 1 || true)"
		if [[ -z "$fallback" ]]; then
			fallback="$(find "$ROOT_DIR/app/release" -maxdepth 1 -name "MQLTV-*-release.apk" 2>/dev/null | head -n 1 || true)"
		fi
	fi

	if [[ -n "$fallback" && -f "$fallback" ]]; then
		echo "$fallback"
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
		echo "Error: File APK tidak ditemukan!" >&2
		if [[ "$MODE" == "app" ]]; then
			echo "Jalankan build terlebih dahulu atau tentukan path APK dengan --apk <file.apk>" >&2
		fi
		exit 3
	fi

	echo "$apk"
}

build_if_needed() {
	normalize_variant
	if [[ "$BUILD" -eq 0 || -n "$APK_OVERRIDE" ]]; then
		return 0
	fi

	if [[ "$MODE" == "vidio" ]]; then
		if [[ "$VARIANT" == "debug" ]]; then
			./gradlew :vidio:assembleDebug --no-daemon
		fi
		return 0
	fi

	# Mode app
	if [[ "$VARIANT" == "release" ]]; then
		# If release APK already exists in app/release/, use it without forcing rebuild
		local abi
		abi="$(get_device_abi)"
		if [[ -f "$ROOT_DIR/app/release/MQLTV-${abi}-release.apk" || -f "$ROOT_DIR/app/release/MQLTV-armeabi-v7a-release.apk" ]]; then
			echo "Menggunakan APK release yang sudah tersedia di app/release/."
			return 0
		fi
		./gradlew :app:assembleRelease --no-daemon
	else
		./gradlew :app:assembleDebug --no-daemon
	fi
}

check_xwalk_pkgs() {
	echo "Checking Crosswalk runtime packages on device: $DEV"
	adb -s "$DEV" shell su -c 'pm list packages org.xwalk.core || true' 2>/dev/null || \
		adb -s "$DEV" shell 'pm list packages org.xwalk.core || true'
	adb -s "$DEV" shell su -c 'pm path org.xwalk.core || true' 2>/dev/null || \
		adb -s "$DEV" shell 'pm path org.xwalk.core || true'
}

install_to_system_priv_app() {
	local apk="$1"
	local dest="/system/priv-app/MQLTV.apk"
	local tmp_on_device="/data/local/tmp/sys_mqltv.apk"

	echo "Memasang langsung ke sistem ($dest)..."
	# Remount /system rw
	adb -s "$DEV" remount >/dev/null 2>&1 || \
		adb -s "$DEV" shell "su -c 'mount -o remount,rw /system'" >/dev/null 2>&1 || true

	adb -s "$DEV" push "$apk" "$tmp_on_device" >/dev/null
	adb -s "$DEV" shell "su -c 'cp \"$tmp_on_device\" \"$dest\" && chmod 0644 \"$dest\" && chown 0:0 \"$dest\" && rm -f \"$tmp_on_device\"'"
	echo "Selesai memperbarui $dest."
}

install_apk() {
	local apk="$1"
	local tmp_on_device="/data/local/tmp/pushapp.apk"
	local sdk
	sdk="$(get_device_sdk)"

	if [[ "$INSTALL_TO_SYSTEM" -eq 1 ]]; then
		install_to_system_priv_app "$apk"
		return 0
	fi

	echo "Menginstall ke $DEV (Android SDK $sdk): $(basename "$apk")"

	# Build flag compatibility:
	# KitKat (SDK 19) pm does NOT support -d (downgrade flag). It throws: "Error: Unknown option -d"
	# SDK >= 21 supports -r -d -t
	local install_flags=("-r")
	if [[ "$sdk" -ge 17 ]]; then
		install_flags+=("-t")
	fi
	if [[ "$sdk" -ge 21 ]]; then
		install_flags+=("-d")
	fi

	# Disable SELinux enforcing on rooted STBs to prevent installation blocks
	adb -s "$DEV" shell "su -c 'setenforce 0'" >/dev/null 2>&1 || true

	local out
	set +e
	out="$(adb -s "$DEV" install "${install_flags[@]}" "$apk" 2>&1)"
	local rc=$?
	set -e
	echo "$out"

	if [[ $rc -eq 0 && "$out" == *"Success"* ]]; then
		return 0
	fi

	echo "adb install standar tidak berhasil. Mencoba fallback via 'su -c pm install'..."
	adb -s "$DEV" push "$apk" "$tmp_on_device" >/dev/null
	adb -s "$DEV" shell "su -c 'chmod 644 \"$tmp_on_device\"'" >/dev/null 2>&1 || true

	set +e
	local pm_flags_str="${install_flags[*]}"
	out="$(adb -s "$DEV" shell "su -c 'pm install $pm_flags_str \"$tmp_on_device\"'" 2>&1)"
	rc=$?
	set -e
	echo "$out"
	adb -s "$DEV" shell "su -c 'rm -f \"$tmp_on_device\"'" >/dev/null 2>&1 || true

	if [[ $rc -eq 0 && "$out" == *"Success"* ]]; then
		return 0
	fi

	# Check for common KitKat system app conflict (INSTALL_FAILED_UPDATE_INCOMPATIBLE or signature mismatch)
	if [[ "$out" == *"INSTALL_FAILED_UPDATE_INCOMPATIBLE"* || "$out" == *"INSTALL_FAILED_SHARED_USER_INCOMPATIBLE"* ]]; then
		echo "" >&2
		echo "PERINGATAN: Terdeteksi konflik signature dengan MQLTV lama di /system/priv-app/." >&2
		echo "Untuk menimpa langsung file sistem, jalankan:" >&2
		echo "  $0 --system --apk \"$apk\"" >&2
	fi

	return $rc
}

launch_app() {
	local component=""
	if [[ "$MODE" == "vidio" ]]; then
		component="com.sihiver.vidio/.MainActivity"
	else
		component="com.mqltv/.MainActivity"
	fi
	echo "Menjalankan aplikasi di STB: $component"
	adb -s "$DEV" shell am start -n "$component" >/dev/null
}

# --- Execution Flow ---
ensure_connected
setup_tcpip_if_requested
build_if_needed

if [[ "$CHECK_XWALK" -eq 1 ]]; then
	check_xwalk_pkgs
fi

APK="$(ensure_apk)"
install_apk "$APK"

if [[ "$LAUNCH" -eq 1 ]]; then
	launch_app
fi

echo "Selesai."
