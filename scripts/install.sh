#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail
ROOT="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
APK="$ROOT/release/Movia-0.9.32-code302.apk"
while [ "$#" -gt 0 ]; do
  case "$1" in
    --apk) shift; APK="${1:-}" ;;
    *) echo "Usage: scripts/install.sh [--apk PATH]"; exit 2 ;;
  esac
  shift
done
[ -f "$APK" ] || { echo "FAIL: APK not found: $APK"; exit 1; }
echo "Installing: $APK"
sha256sum "$APK"

if command -v rish >/dev/null 2>&1 && rish -c 'id' >/dev/null 2>&1; then
  remote=/data/local/tmp/movia-recovery.apk
  cat "$APK" | rish -c "cat > $remote"
  local_sha="$(sha256sum "$APK" | awk '{print $1}')"
  remote_sha="$(rish -c "sha256sum $remote" | awk '{print $1}')"
  [ "$local_sha" = "$remote_sha" ] || { rish -c "rm -f $remote" || true; echo "FAIL: remote APK hash mismatch"; exit 1; }
  rish -c "pm install -r --user 0 $remote"
  rish -c "rm -f $remote"
elif command -v adb >/dev/null 2>&1; then
  adb install -r "$APK"
else
  echo "FAIL: neither Shizuku/rish nor adb is available"
  exit 1
fi

echo "Installed app.movia.android without an explicit data-clear step."
