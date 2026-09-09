#!/data/data/com.termux/files/usr/bin/bash
set -u
full=0
package=0
for arg in "$@"; do
  case "$arg" in
    --full) full=1 ;;
    --package) package=1 ;;
    *) echo "Usage: scripts/health-check.sh [--full] [--package]"; exit 2 ;;
  esac
done
failures=0
check_http() {
  label="$1"; url="$2"; expected="$3"
  code="$(curl -sS --max-time 5 -o /dev/null -w '%{http_code}' "$url" 2>/dev/null || true)"
  if [ "$code" = "$expected" ]; then echo "PASS: $label ($code)"; else echo "FAIL: $label expected $expected, got ${code:-no-response}"; failures=$((failures+1)); fi
}

check_http "Movia control plane" "${MOVIA_STREAMER_HEALTH_URL:-http://127.0.0.1:8888/health}" 200
check_http "TorrServer" "${MOVIA_TORRSERVER_HEALTH_URL:-http://127.0.0.1:18090/echo}" 200

if [ "$full" -eq 1 ]; then
  check_http "Termux MCP health" "${MOVIA_MCP_HEALTH_URL:-http://127.0.0.1:8940/healthz}" 200
fi

if [ "$package" -eq 1 ]; then
  info=""
  if command -v rish >/dev/null 2>&1 && rish -c 'id' >/dev/null 2>&1; then
    info="$(rish -c 'dumpsys package app.movia.android' 2>/dev/null || true)"
  elif command -v adb >/dev/null 2>&1; then
    info="$(adb shell dumpsys package app.movia.android 2>/dev/null || true)"
  fi
  printf '%s
' "$info" | grep -q 'versionCode=302' && echo "PASS: package versionCode 302" || { echo "FAIL: package versionCode 302"; failures=$((failures+1)); }
  printf '%s
' "$info" | grep -q 'versionName=0.9.32' && echo "PASS: package versionName 0.9.32" || { echo "FAIL: package versionName 0.9.32"; failures=$((failures+1)); }
fi

[ "$failures" -eq 0 ] && { echo PASS; exit 0; }
echo "FAIL: $failures health check(s)"
exit 1
