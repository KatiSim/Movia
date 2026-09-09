#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail
KEYSTORE="${MOVIA_KEYSTORE:-$HOME/.android/debug.keystore}"
if [ -f "$KEYSTORE" ]; then
  echo "Keystore already present: $KEYSTORE"
  exit 0
fi
command -v keytool >/dev/null 2>&1 || { echo "FAIL: keytool is required (install OpenJDK)"; exit 1; }
mkdir -p "$(dirname "$KEYSTORE")"
keytool -genkeypair -v   -keystore "$KEYSTORE"   -storepass android   -alias androiddebugkey   -keypass android   -dname "CN=Android Debug,O=Android,C=US"   -keyalg RSA -keysize 2048 -validity 10000
chmod 600 "$KEYSTORE" || true
echo "Created development keystore: $KEYSTORE"
echo "NOTE: a newly generated key can reinstall after uninstall, but cannot update an APK signed by a different existing key."
