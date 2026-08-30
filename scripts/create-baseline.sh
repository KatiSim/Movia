#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

ROOT="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
ANDROID_SRC="$HOME/projects/movia"
BACKEND_SRC="$HOME/projects/media-parser"
AGENT_SRC="$HOME/termux-mcp"
[ -z "$(printenv MOVIA_ANDROID_SOURCE || true)" ] || ANDROID_SRC="$(printenv MOVIA_ANDROID_SOURCE)"
[ -z "$(printenv MOVIA_BACKEND_SOURCE || true)" ] || BACKEND_SRC="$(printenv MOVIA_BACKEND_SOURCE)"
[ -z "$(printenv MOVIA_AGENT_SOURCE || true)" ] || AGENT_SRC="$(printenv MOVIA_AGENT_SOURCE)"
APK="$ROOT/release/Movia-0.9.23-code293.apk"
[ -z "$(printenv MOVIA_APK || true)" ] || APK="$(printenv MOVIA_APK)"
[ -f "$APK" ] || APK="$ANDROID_SRC/app-0.9.23-code293.apk"
DB="$BACKEND_SRC/catalog.db"
[ -z "$(printenv MOVIA_CATALOG_DB || true)" ] || DB="$(printenv MOVIA_CATALOG_DB)"
PYTHON_BIN="$(printenv PYTHON_BIN || echo python)"

[ -f "$ROOT/android/app/build.gradle.kts" ] || { echo "FAIL: Android build file missing"; exit 1; }
[ -f "$APK" ] || { echo "FAIL: current APK not found: $APK"; exit 1; }

version_name="$(sed -n 's/^[[:space:]]*versionName[[:space:]]*=[[:space:]]*"\([^"]*\)".*/\1/p' "$ROOT/android/app/build.gradle.kts" | head -1)"
version_code="$(sed -n 's/^[[:space:]]*versionCode[[:space:]]*=[[:space:]]*\([0-9][0-9]*\).*/\1/p' "$ROOT/android/app/build.gradle.kts" | head -1)"
package_name="$(sed -n 's/^[[:space:]]*applicationId[[:space:]]*=[[:space:]]*"\([^"]*\)".*/\1/p' "$ROOT/android/app/build.gradle.kts" | head -1)"
apk_sha="$(sha256sum "$APK" | awk '{print $1}')"
apk_size="$(stat -c '%s' "$APK")"
git_commit="$(git -c safe.directory="$ROOT" -C "$ROOT" rev-parse HEAD 2>/dev/null || true)"
source_commit="$(git -C "$ANDROID_SRC" rev-parse HEAD 2>/dev/null || true)"
created_at="$(date -u +%Y-%m-%dT%H:%M:%SZ)"

export ROOT ANDROID_SRC BACKEND_SRC AGENT_SRC APK DB version_name version_code package_name apk_sha apk_size git_commit source_commit created_at
"$PYTHON_BIN" - <<'PY'
import json, os, sqlite3
from pathlib import Path

def clean(value):
    return value if value else None

db = os.environ["DB"]
catalog_rows = None
schema_version = None
catalog_revision = None
normalization_version = None
db_status = "missing"
if Path(db).is_file():
    try:
        con = sqlite3.connect("file:" + db + "?mode=ro", uri=True, timeout=5)
        values = dict(con.execute("SELECT key, value FROM catalog_meta"))
        catalog_rows = con.execute("SELECT COUNT(*) FROM movies").fetchone()[0]
        schema_version = values.get("schema_version")
        catalog_revision = values.get("catalog_revision")
        normalization_version = values.get("normalization_version")
        con.close()
        db_status = "read-only metadata query succeeded"
    except Exception as exc:
        db_status = "read failed: " + type(exc).__name__
manifest = {
    "project": "Movia",
    "package": clean(os.environ["package_name"]),
    "versionName": clean(os.environ["version_name"]),
    "versionCode": int(os.environ["version_code"]) if os.environ["version_code"] else None,
    "apkPath": os.path.abspath(os.environ["APK"]),
    "apkSizeBytes": int(os.environ["apk_size"]),
    "apkSha256": os.environ["apk_sha"],
    "catalogRows": catalog_rows,
    "catalogSchemaVersion": schema_version,
    "catalogRevision": catalog_revision,
    "catalogNormalizationVersion": normalization_version,
    "catalogDbPath": os.path.abspath(db),
    "catalogDbStatus": db_status,
    "backendService": "movia-media-parser / streamer.py",
    "agentSchemaVersion": 2,
    "nativeMoviaMcpTools": 29,
    "sourceRoots": {
        "android": os.environ["ANDROID_SRC"],
        "backend": os.environ["BACKEND_SRC"],
        "agentMcp": os.environ["AGENT_SRC"],
    },
    "sourceCommit": clean(os.environ["source_commit"]),
    "gitCommit": clean(os.environ["git_commit"]),
    "createdAt": os.environ["created_at"],
    "verification": {
        "build": os.environ.get("MOVIA_VERIFICATION_BUILD", "not-run"),
        "backend": os.environ.get("MOVIA_VERIFICATION_BACKEND", "not-run"),
        "agent": os.environ.get("MOVIA_VERIFICATION_AGENT", "not-run"),
        "playback": os.environ.get("MOVIA_VERIFICATION_PLAYBACK", "not-run"),
    },
}
out = Path(os.environ["ROOT"]) / "CURRENT_BASELINE.json"
out.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
PY

mkdir -p "$ROOT/release"
printf '%s  %s\n' "$apk_sha" "release/$(basename "$APK")" > "$ROOT/release/SHA256SUMS.txt"

snapshot_flag="$(printenv MOVIA_DB_SNAPSHOT || echo 0)"
if [ "$snapshot_flag" = "1" ]; then
  snapshot_name="catalog-"$version_name"-code"$version_code".sqlite.gz"
  snapshot_out="$ROOT/release/$snapshot_name"
  [ -z "$(printenv MOVIA_SNAPSHOT_OUT || true)" ] || snapshot_out="$(printenv MOVIA_SNAPSHOT_OUT)"
  tmp_dir="$(printenv TMPDIR || echo /data/data/com.termux/files/usr/tmp)"
  tmp_db="$(mktemp "$tmp_dir/movia-catalog.XXXXXX.sqlite")"
  trap 'rm -f -- "$tmp_db"' EXIT
  export DB SNAPSHOT_DB="$tmp_db"
  "$PYTHON_BIN" - <<'PY'
import os, sqlite3
src = sqlite3.connect("file:" + os.environ["DB"] + "?mode=ro", uri=True, timeout=20)
dst = sqlite3.connect(os.environ["SNAPSHOT_DB"])
src.backup(dst)
dst.close()
src.close()
PY
  gzip -c "$tmp_db" > "$snapshot_out"
  gzip_path="release/$(basename "$snapshot_out")"
  sha256sum "$snapshot_out" | sed "s#  $snapshot_out#  $gzip_path#" >> "$ROOT/release/SHA256SUMS.txt"
  echo "DB_SNAPSHOT=$snapshot_out"
fi

echo "CURRENT_BASELINE=$ROOT/CURRENT_BASELINE.json"
echo "APK_SHA256=$apk_sha"
echo "CATALOG_DB=$DB"
