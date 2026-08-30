#!/data/data/com.termux/files/usr/bin/bash
set -e

APK_PATH="$1"
if [ -z "$APK_PATH" ]; then
    APK_PATH="/data/data/com.termux/files/home/projects/movia/app/build/outputs/apk/debug/app-debug.apk"
fi

if [ ! -f "$APK_PATH" ]; then
    echo "❌ APK not found at: $APK_PATH"
    exit 1
fi

TMP_APK="/data/local/tmp/app_deploy.apk"
PKG_NAME="app.movia.android"
MAIN_ACTIVITY="app.movia.android/.MainActivity"

echo "🚀 [AutoDeploy] Installing $APK_PATH silently..."

# 1. Try Shizuku rish silent install
if rish -c "cat '$APK_PATH' > '$TMP_APK' && pm install -r -d '$TMP_APK' && rm -f '$TMP_APK' && am force-stop '$PKG_NAME' && am start -n '$MAIN_ACTIVITY'" 2>/dev/null; then
    echo "✅ [AutoDeploy] Successfully installed and launched via Shizuku rish (zero clicks)!"
    exit 0
fi

# 2. Try ADB localhost silent install
if adb install -r -d "$APK_PATH" 2>/dev/null; then
    adb shell am force-stop "$PKG_NAME"
    adb shell am start -n "$MAIN_ACTIVITY"
    echo "✅ [AutoDeploy] Successfully installed and launched via ADB (zero clicks)!"
    exit 0
fi

# 3. Fallback: Wake Shizuku app
echo "⚠️ [AutoDeploy] Shizuku service needs to be active. Waking Shizuku..."
am start -n moe.shizuku.privileged.api/moe.shizuku.manager.MainActivity 2>/dev/null || true
termux-open "$APK_PATH" 2>/dev/null || true
