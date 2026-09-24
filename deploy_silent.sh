#!/data/data/com.termux/files/usr/bin/bash
set -e
set -o pipefail

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

# 1. Try Shizuku rish silent install.
# rish runs as Android shell and cannot read Termux private /data/data/... paths directly.
# Stream APK bytes from Termux stdin into a shell-readable temp file, then verify size.
LOCAL_SIZE="$(stat -c %s "$APK_PATH")"
if cat "$APK_PATH" | rish -c "cat > '$TMP_APK'"; then
    TMP_SIZE="$(rish -c "stat -c %s '$TMP_APK'" 2>/dev/null | tr -d '\r')"
    if [ "$TMP_SIZE" = "$LOCAL_SIZE" ] && [ "$LOCAL_SIZE" -gt 0 ]; then
        INSTALL_OUTPUT="$(rish -c "pm install -r -d '$TMP_APK'" 2>&1)"
        if printf '%s\n' "$INSTALL_OUTPUT" | grep -q '^Success$'; then
            rish -c "rm -f '$TMP_APK'; am force-stop '$PKG_NAME'; am start -n '$MAIN_ACTIVITY'" >/dev/null
            echo "✅ [AutoDeploy] Successfully installed and launched via Shizuku rish (verified $LOCAL_SIZE bytes)."
            exit 0
        fi
        printf '%s\n' "$INSTALL_OUTPUT" >&2
    else
        echo "❌ [AutoDeploy] APK transfer verification failed: local=$LOCAL_SIZE tmp=${TMP_SIZE:-missing}" >&2
    fi
fi
rish -c "rm -f '$TMP_APK'" >/dev/null 2>&1 || true

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
