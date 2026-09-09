#!/data/data/com.termux/files/usr/bin/bash
set -u
ROOT="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
failures=0
ok(){ echo "PASS: $1"; }
bad(){ echo "FAIL: $1"; failures=$((failures+1)); }
need_file(){ [ -f "$ROOT/$1" ] && ok "$1" || bad "missing $1"; }
need_dir(){ [ -d "$ROOT/$1" ] && ok "$1" || bad "missing directory $1"; }

for d in android backend agent database docs scripts release cloud; do need_dir "$d"; done
for f in README.md RESTORE.md PROJECT_STATE.md CURRENT_BASELINE.json SECRETS_SETUP.md CHANGELOG.md .env.example config.example; do need_file "$f"; done
for f in   release/Movia-0.9.32-code302.apk release/SHA256SUMS.txt   docs/DESIGN_SYSTEM_0.9.32.md docs/INTERACTION_LOGIC_0.9.32.md docs/RECOVERY_BLUEPRINT_0.9.32.md   android/app/build.gradle.kts android/gradlew backend/requirements.txt backend/streamer.py backend/catalog_api.py   agent/runtime/aria2.conf.example agent/services/movia-media-parser/run agent/services/movia-torrserver/run   scripts/setup-local-runtime.sh scripts/bootstrap-debug-keystore.sh scripts/export-runtime-catalog.sh scripts/import-runtime-catalog.sh; do need_file "$f"; done

expected="25e9c2a3a49e4649376b871f469bec3df39160c7ef86743d317a41855f23f49b"
actual="$(sha256sum "$ROOT/release/Movia-0.9.32-code302.apk" 2>/dev/null | awk '{print $1}')"
[ "$actual" = "$expected" ] && ok "canonical APK SHA-256" || bad "canonical APK SHA-256 is $actual"

python3 - "$ROOT/CURRENT_BASELINE.json" <<'PY2' >/dev/null 2>&1
import json,sys
x=json.load(open(sys.argv[1]))
assert x['versionName']=='0.9.32' and x['versionCode']==302
assert x['apk']['sha256']=='25e9c2a3a49e4649376b871f469bec3df39160c7ef86743d317a41855f23f49b'
PY2
[ "$?" -eq 0 ] && ok "CURRENT_BASELINE.json" || bad "CURRENT_BASELINE.json"

tracked_bad="$(cd "$ROOT" && git -c safe.directory="$ROOT" ls-files | grep -E '(^|/)(\.env|.*\.db(-wal|-shm)?|.*\.keystore)$' || true)"
[ -z "$tracked_bad" ] && ok "no private env/DB/keystore tracked" || { echo "$tracked_bad"; bad "private runtime file tracked"; }

for f in "$ROOT"/scripts/*.sh "$ROOT"/agent/services/*/run "$ROOT"/agent/services/*/log/run; do
  [ -f "$f" ] || continue
  bash -n "$f" || bad "shell syntax: ${f#$ROOT/}"
done

[ "$failures" -eq 0 ] && { echo PASS; exit 0; }
echo "FAIL: $failures restore check(s)"
exit 1
