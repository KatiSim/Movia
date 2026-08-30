#!/data/data/com.termux/files/usr/bin/bash
set -u

ROOT="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
failures=0
ok() { echo "PASS: $1"; }
bad() { echo "FAIL: $1"; failures=$((failures + 1)); }
need_file() { [ -f "$ROOT/$1" ] && ok "$1" || bad "missing $1"; }
need_dir() { [ -d "$ROOT/$1" ] && ok "$1" || bad "missing directory $1"; }

for d in android backend agent database acceptance docs scripts release reference; do need_dir "$d"; done
for f in README.md CHANGELOG.md PROJECT_STATE.md RESTORE.md CURRENT_BASELINE.json .gitignore SECRETS_SETUP.md .env.example config.example; do need_file "$f"; done
for f in android/app/build.gradle.kts android/settings.gradle.kts android/gradlew backend/server.py backend/database.py backend/search_engine.py backend/streamer.py backend/requirements.txt; do need_file "$f"; done
for f in agent/mcp/package.json agent/mcp/src/server.ts agent/mcp/src/movia-tools.ts agent/services/movia-media-parser/run database/CATALOG_DB_STATUS.md database/schema/SCHEMA_SOURCE.md android/app/schemas/app.movia.android.data.database.MoviaDatabase/2.json; do need_file "$f"; done

version_name="$(sed -n 's/^[[:space:]]*versionName[[:space:]]*=[[:space:]]*"\([^"]*\)".*/\1/p' "$ROOT/android/app/build.gradle.kts" | head -1)"
version_code="$(sed -n 's/^[[:space:]]*versionCode[[:space:]]*=[[:space:]]*\([0-9][0-9]*\).*/\1/p' "$ROOT/android/app/build.gradle.kts" | head -1)"
package_name="$(sed -n 's/^[[:space:]]*applicationId[[:space:]]*=[[:space:]]*"\([^"]*\)".*/\1/p' "$ROOT/android/app/build.gradle.kts" | head -1)"
[ "$version_name" = "0.9.23" ] && ok "versionName 0.9.23" || bad "versionName is $version_name"
[ "$version_code" = "293" ] && ok "versionCode 293" || bad "versionCode is $version_code"
[ "$package_name" = "app.movia.android" ] && ok "package app.movia.android" || bad "package is $package_name"

tool_count="$(awk '/server\.registerTool\(/ {n++} END {print n+0}' "$ROOT/agent/mcp/src/movia-tools.ts" 2>/dev/null)"
[ "$tool_count" = "29" ] && ok "29 native Movia MCP tools" || bad "native Movia MCP tool count is $tool_count"
grep -q 'MOVIA_AGENT_SCHEMA_VERSION = 2' "$ROOT/android/app/src/main/java/app/movia/android/agent/AgentModels.kt" 2>/dev/null && ok "native agent schema 2" || bad "native agent schema 2 missing"

if find "$ROOT" -type f \( -name '.env' -o -name '*.db' -o -name '*.db-*' -o -name '*.log' -o -name '*.pid' \) -print -quit | grep -q .; then
  bad "runtime data/cache/log file present in canonical tree"
else
  ok "no DB/cache/log runtime payload in canonical tree"
fi

if rg -n -I --glob '!*.apk' --glob '!*.png' --glob '!*.jpg' --glob '!*.jpeg' --glob '!*.json' -E 'ghp_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{20,}|BEGIN (RSA|OPENSSH|EC|DSA) PRIVATE KEY|Authorization:[[:space:]]*Bearer[[:space:]]+[A-Za-z0-9._-]{12,}' "$ROOT" >/dev/null 2>&1; then
  bad "secret pattern found"
else
  ok "secret scan"
fi

if command -v python >/dev/null 2>&1; then
  if python - "$ROOT/backend" <<'PY'
import ast, pathlib, sys
root = pathlib.Path(sys.argv[1])
for p in root.rglob("*.py"):
    ast.parse(p.read_text(encoding="utf-8"), filename=str(p))
PY
  then ok "backend Python syntax"; else bad "backend Python syntax"; fi
else
  bad "python missing for backend syntax"
fi

if [ -f "$ROOT/scripts/health-check.sh" ]; then
  if bash "$ROOT/scripts/health-check.sh"; then ok "live services"; else bad "live services"; fi
else
  bad "health-check.sh not executable"
fi

if [ "$failures" -eq 0 ]; then
  echo PASS
  exit 0
fi
echo "FAIL: $failures project check(s)"
exit 1
