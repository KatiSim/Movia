#!/data/data/com.termux/files/usr/bin/bash
set -u
ROOT="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
live=0
[ "${1:-}" = "--live" ] && live=1
failures=0
ok(){ echo "PASS: $1"; }
bad(){ echo "FAIL: $1"; failures=$((failures+1)); }

version_name="$(awk -F'"' '/versionName[[:space:]]*=/ {print $2; exit}' "$ROOT/android/app/build.gradle.kts")"
version_code="$(awk '/versionCode[[:space:]]*=/ {print $3; exit}' "$ROOT/android/app/build.gradle.kts")"
package_name="$(awk -F'"' '/applicationId[[:space:]]*=/ {print $2; exit}' "$ROOT/android/app/build.gradle.kts")"
[ "$version_name" = "0.9.32" ] && ok "versionName 0.9.32" || bad "versionName $version_name"
[ "$version_code" = "302" ] && ok "versionCode 302" || bad "versionCode $version_code"
[ "$package_name" = "app.movia.android" ] && ok "package app.movia.android" || bad "package $package_name"

tool_count="$(awk '/server\.registerTool\(/ {n++} END {print n+0}' "$ROOT/agent/mcp/src/movia-tools.ts")"
[ "$tool_count" = "30" ] && ok "30 Movia MCP tools" || bad "MCP tool count $tool_count"
grep -q 'MOVIA_AGENT_SCHEMA_VERSION = 2' "$ROOT/android/app/src/main/java/app/movia/android/agent/AgentModels.kt" && ok "agent schema 2" || bad "agent schema 2"

expected_apk="25e9c2a3a49e4649376b871f469bec3df39160c7ef86743d317a41855f23f49b"
actual_apk="$(sha256sum "$ROOT/release/Movia-0.9.32-code302.apk" 2>/dev/null | awk '{print $1}')"
[ "$actual_apk" = "$expected_apk" ] && ok "canonical APK exact hash" || bad "canonical APK hash"

python3 - "$ROOT/backend" <<'PY2'
import ast,pathlib,sys
for p in pathlib.Path(sys.argv[1]).rglob('*.py'):
    ast.parse(p.read_text(encoding='utf-8'), filename=str(p))
PY2
[ "$?" -eq 0 ] && ok "backend Python syntax" || bad "backend Python syntax"

if grep -RIl --exclude='*.apk' --exclude='*.png' -E 'BEGIN (RSA|OPENSSH|EC|DSA) PRIVATE KEY|ghp_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{20,}' "$ROOT" >/dev/null 2>&1; then
  bad "high-confidence secret pattern found"
else
  ok "high-confidence secret scan"
fi

(cd "$ROOT" && git -c safe.directory="$ROOT" diff --check) >/dev/null 2>&1 && ok "git diff --check" || bad "git diff --check"

if [ "$live" -eq 1 ]; then
  bash "$ROOT/scripts/health-check.sh" --full --package || bad "live health"
fi

[ "$failures" -eq 0 ] && { echo PASS; exit 0; }
echo "FAIL: $failures project check(s)"
exit 1
