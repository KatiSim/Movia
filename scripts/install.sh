#!/data/data/com.termux/files/usr/bin/bash
set -eu

apk_path=$1
if [ -z "$apk_path" ] || [ ! -f "$apk_path" ]; then
  echo "FAIL: APK path is required and must point to a file" >&2
  exit 1
fi
if ! command -v rish >/dev/null 2>&1; then
  echo "FAIL: rish is required for a non-destructive Android install" >&2
  exit 1
fi
apk_size=$(stat -c '%s' -- "$apk_path")
if [ -z "$apk_size" ]; then
  echo "FAIL: could not determine APK size" >&2
  exit 1
fi

echo "Installing without clearing app data"
rish -c "pm install -r -S $apk_size" < "$apk_path"

if ! rish -c 'pm path app.movia.android' 2>/dev/null | grep -q '^package:'; then
  echo "FAIL: package app.movia.android is not installed after install" >&2
  exit 1
fi
echo "PASS"
