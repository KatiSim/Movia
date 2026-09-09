#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail
ROOT="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/android-sdk}"
export ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-$ANDROID_HOME}"
[ -d "$ANDROID_HOME" ] || { echo "FAIL: Android SDK not found: $ANDROID_HOME"; exit 1; }
bash "$ROOT/scripts/bootstrap-debug-keystore.sh"
cd "$ROOT/android"
./gradlew --no-daemon :app:assembleDebug
APK="$ROOT/android/app/build/outputs/apk/debug/app-debug.apk"
[ -f "$APK" ] || { echo "FAIL: APK not found: $APK"; exit 1; }
echo "Built: $APK"
sha256sum "$APK"
echo "Canonical recovery APK remains untouched: $ROOT/release/Movia-0.9.32-code302.apk"
