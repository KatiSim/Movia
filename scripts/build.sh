#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail
ROOT="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
cd "$ROOT/android"
./gradlew --no-daemon assembleDebug
APK="$ROOT/android/app/build/outputs/apk/debug/app-debug.apk"
if [ -f "$APK" ]; then
  mkdir -p "$ROOT/release"
  cp -p "$APK" "$ROOT/release/Movia-debug.apk"
  sha256sum "$ROOT/release/Movia-debug.apk"
  echo "Built: $APK"
else
  echo "FAIL: Gradle completed but APK was not found: $APK"
  exit 1
fi
