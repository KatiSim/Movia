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
  if [ ! -f "$root/$item" ]; then fail "missing restore file: $item"; fi
done

if [ ! -f "$root/android/settings.gradle.kts" ] || [ ! -f "$root/android/app/build.gradle.kts" ] || [ ! -x "$root/android/gradlew" ]; then
  fail "Android build source is incomplete"
fi
if ! find "$root/backend" -type f \( -name '*.py' -o -name '*.js' -o -name '*.ts' \) -print -quit 2>/dev/null | grep -q .; then
  fail "backend source is not restorable from this checkout"
fi
if ! find "$root/agent" -type f \( -name '*.py' -o -name '*.js' -o -name '*.ts' \) -print -quit 2>/dev/null | grep -q .; then
  fail "Movia Agent source is not restorable from this checkout"
fi
if ! find "$root/agent" -type f -iname '*mcp*' -print -quit 2>/dev/null | grep -q .; then
  fail "Movia MCP integration is not restorable from this checkout"
fi
if [ ! -e "$root/database/schema" ] || [ ! -d "$root/database/migrations" ]; then
  fail "database schema/migrations are incomplete"
fi
if [ ! -f "$root/.env.example" ] || [ ! -f "$root/config.example" ] || [ ! -f "$root/SECRETS_SETUP.md" ]; then
  fail "secret/configuration instructions are incomplete"
fi
if ! find "$root/release" -type f -name '*.apk' -print -quit 2>/dev/null | grep -q .; then
  fail "baseline APK artifact is not available locally; source can be rebuilt but binary restore is incomplete"
fi

if [ "$failures_count" -ne 0 ]; then
  echo "FAIL"
  printf '%s\n' "$failures"
  exit 1
fi
echo "PASS"
