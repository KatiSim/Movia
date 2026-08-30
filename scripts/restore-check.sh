#!/data/data/com.termux/files/usr/bin/bash
set -u

ROOT="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
failures=0
need_file() { [ -f "$ROOT/$1" ] && echo "PASS: $1" || { echo "FAIL: missing $1"; failures=$((failures+1)); }; }
need_dir() { [ -d "$ROOT/$1" ] && echo "PASS: $1" || { echo "FAIL: missing directory $1"; failures=$((failures+1)); }; }

for d in android backend agent database acceptance docs scripts reference; do need_dir "$d"; done
for f in README.md CHANGELOG.md PROJECT_STATE.md RESTORE.md CURRENT_BASELINE.json .gitignore SECRETS_SETUP.md .env.example config.example; do need_file "$f"; done
for f in android/app/build.gradle.kts android/gradlew backend/requirements.txt backend/catalog_schema_v2.py backend/restore_all.py backend/database.py agent/mcp/package.json agent/mcp/src/server.ts database/CATALOG_DB_STATUS.md database/schema/app.movia.android.data.database.MoviaDatabase/2.json agent/services/movia-media-parser/run; do need_file "$f"; done

if grep -q '"apkSha256"' "$ROOT/CURRENT_BASELINE.json" 2>/dev/null; then echo "PASS: baseline APK manifest"; else echo "FAIL: baseline APK manifest"; failures=$((failures+1)); fi
if grep -q 'catalogDbPath' "$ROOT/CURRENT_BASELINE.json" 2>/dev/null && grep -q 'schema_version' "$ROOT/database/CATALOG_DB_STATUS.md" 2>/dev/null; then echo "PASS: external catalog recovery metadata"; else echo "FAIL: external catalog recovery metadata"; failures=$((failures+1)); fi
if grep -q 'SHA256' "$ROOT/release/README.md" 2>/dev/null; then echo "PASS: release checksum instructions"; else echo "FAIL: release checksum instructions"; failures=$((failures+1)); fi
if find "$ROOT" -type f \( -name '.env' -o -name '*.db' -o -name '*.db-*' \) -print -quit | grep -q .; then echo "FAIL: raw DB or secret env is inside repository"; failures=$((failures+1)); else echo "PASS: raw DB and .env excluded"; fi

if [ "$failures" -eq 0 ]; then echo PASS; exit 0; fi
echo "FAIL: $failures restore check(s)"
exit 1
