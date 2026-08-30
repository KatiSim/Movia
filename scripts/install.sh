#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail
ROOT="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
APK="$ROOT/release/Movia-0.9.23-code293.apk"
while [ "$#" -gt 0 ]; do
  case "$1" in
    --apk) shift; APK="$1" ;;
    *) echo "Usage: scripts/install.sh [--apk PATH]"; exit 2 ;;
  esac
  shift
done
[ -f "$APK" ] || { echo "FAIL: APK not found: $APK"; exit 1; }
command -v adb >/dev/null 2>&1 || { echo "FAIL: adb not found"; exit 1; }
adb install -r "$APK"
echo "Installed without clearing data: $APK"
