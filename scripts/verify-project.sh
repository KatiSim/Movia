#!/data/data/com.termux/files/usr/bin/bash
set -u

script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
root=$(CDPATH= cd -- "$script_dir/.." && pwd)
failures_count=0
failures=

fail() {
  failures_count=$((failures_count + 1))
  if [ -n "$failures" ]; then
    failures="$failures
- $1"
  else
    failures="- $1"
  fi
}

for item in android backend agent database acceptance docs scripts release reference; do
  if [ ! -e "$root/$item" ]; then fail "missing directory: $item"; fi
done

for item in README.md CHANGELOG.md PROJECT_STATE.md RESTORE.md CURRENT_BASELINE.json .gitignore .env.example config.example SECRETS_SETUP.md; do
  if [ ! -f "$root/$item" ]; then fail "missing root file: $item"; fi
done

if [ ! -f "$root/android/settings.gradle.kts" ] || [ ! -f "$root/android/app/build.gradle.kts" ] || [ ! -f "$root/android/app/src/main/AndroidManifest.xml" ] || [ ! -x "$root/android/gradlew" ]; then
  fail "Android source or Gradle wrapper is incomplete"
fi
if [ ! -L "$root/android/recovered-jadx" ]; then fail "recovered JADX source link is missing"; fi

if ! find "$root/backend" -type f \( -name '*.py' -o -name '*.js' -o -name '*.ts' \) -print -quit 2>/dev/null | grep -q .; then
  fail "current backend/media-parser source is absent"
fi
if [ ! -e "$root/database/schema" ]; then fail "database schema is absent"; fi
if [ ! -d "$root/database/migrations" ]; then fail "database migrations directory is absent"; fi

if ! find "$root/agent" -type f \( -name '*.py' -o -name '*.js' -o -name '*.ts' \) -print -quit 2>/dev/null | grep -q .; then
  fail "current Movia Agent runtime source is absent"
fi
if ! find "$root/agent" -type f -iname '*mcp*' -print -quit 2>/dev/null | grep -q .; then
  fail "Movia MCP integration is absent"
fi
if ! find "$root" -type f -name '*.service' -print -quit 2>/dev/null | grep -q .; then
  fail "current service definitions are absent"
fi

if ! node -e 'JSON.parse(require("fs").readFileSync(process.argv[1], "utf8"))' "$root/CURRENT_BASELINE.json" >/dev/null 2>&1; then
  fail "CURRENT_BASELINE.json is missing or invalid JSON"
fi
if ! find "$root/release" -type f -name '*.apk' -print -quit 2>/dev/null | grep -q .; then
  fail "current APK release artifact is absent"
fi

if command -v rish >/dev/null 2>&1; then
  if ! rish -c 'pm path app.movia.android' 2>/dev/null | grep -q '^package:'; then
    fail "app.movia.android is not installed on the phone"
  fi
else
  fail "rish is unavailable for installed-package verification"
fi

if [ -x "$root/scripts/health-check.sh" ]; then
  if ! "$root/scripts/health-check.sh" >/dev/null 2>&1; then fail "backend and/or agent health check did not PASS"; fi
else
  fail "health-check script is not executable"
fi

if [ "$failures_count" -ne 0 ]; then
  echo "FAIL"
  printf '%s\n' "$failures"
  exit 1
fi
echo "PASS"
