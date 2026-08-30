#!/data/data/com.termux/files/usr/bin/bash
set -u

failures=0
check_http() {
  label="$1"
  url="$2"
  expected="$3"
  code="$(curl -sS --max-time 5 -o /dev/null -w '%{http_code}' "$url" 2>/dev/null || true)"
  if [ "$code" = "$expected" ]; then
    echo "PASS: $label ($code)"
  else
    if [ -n "$code" ]; then shown="$code"; else shown="no-response"; fi
    echo "FAIL: $label expected $expected, got $shown"
    failures=$((failures + 1))
  fi
}

if [ "$#" -gt 0 ] && [ "$1" = "--package" ]; then
  if command -v adb >/dev/null 2>&1; then
    package_info="$(adb shell dumpsys package app.movia.android 2>/dev/null || true)"
    printf '%s\n' "$package_info" | grep -q 'app.movia.android' && echo "PASS: package app.movia.android" || { echo "FAIL: package app.movia.android"; failures=$((failures+1)); }
    printf '%s\n' "$package_info" | grep -q 'versionCode=293' && echo "PASS: versionCode 293" || { echo "FAIL: versionCode 293"; failures=$((failures+1)); }
    printf '%s\n' "$package_info" | grep -q 'versionName=0.9.23' && echo "PASS: versionName 0.9.23" || { echo "FAIL: versionName 0.9.23"; failures=$((failures+1)); }
  else
    echo "FAIL: adb is not installed"
    failures=$((failures + 1))
  fi
fi

parser_url="$(printenv MOVIA_PARSER_HEALTH_URL || echo http://127.0.0.1:5001/health)"
streamer_url="$(printenv MOVIA_STREAMER_HEALTH_URL || echo http://127.0.0.1:8888/health)"
mcp_health_url="$(printenv MOVIA_MCP_HEALTH_URL || echo http://127.0.0.1:8940/healthz)"
mcp_url="$(printenv MOVIA_MCP_URL || echo http://127.0.0.1:8940/mcp)"
check_http "media-parser" "$parser_url" "200"
check_http "streamer" "$streamer_url" "200"
check_http "MCP healthz" "$mcp_health_url" "200"

mcp_code="$(curl -sS --max-time 5 -o /dev/null -w '%{http_code}' "$mcp_url" 2>/dev/null || true)"
case "$mcp_code" in
  200|400|404|405|406|415) echo "PASS: MCP HTTP endpoint reachable ($mcp_code)" ;;
  *) if [ -n "$mcp_code" ]; then shown="$mcp_code"; else shown="no-response"; fi; echo "FAIL: MCP HTTP endpoint expected reachable, got $shown"; failures=$((failures + 1)) ;;
esac

if [ "$failures" -eq 0 ]; then
  echo PASS
  exit 0
fi
echo "FAIL: $failures health check(s)"
exit 1
