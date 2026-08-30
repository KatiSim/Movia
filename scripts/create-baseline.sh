#!/data/data/com.termux/files/usr/bin/bash
set -eu

script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
root=$(CDPATH= cd -- "$script_dir/.." && pwd)
app_gradle="$root/android/app/build.gradle.kts"
apk_path=$(printenv MOVIA_APK || true)
db_path=$(printenv MOVIA_CATALOG_DB || true)
make_db_snapshot=0

while [ "$#" -gt 0 ]; do
  case "$1" in
    --apk)
      shift
      if [ "$#" -eq 0 ]; then echo "FAIL: --apk requires a path" >&2; exit 1; fi
      apk_path=$1
      ;;
    --db)
      shift
      if [ "$#" -eq 0 ]; then echo "FAIL: --db requires a path" >&2; exit 1; fi
      db_path=$1
      make_db_snapshot=1
      ;;
    --db-snapshot)
      make_db_snapshot=1
      ;;
    *)
      echo "FAIL: unknown option: $1" >&2
      exit 1
      ;;
  esac
  shift
done

if [ ! -f "$app_gradle" ]; then
  echo "FAIL: Android Gradle file is missing" >&2
  exit 1
fi

package_name=$(sed -nE 's/.*namespace = "([^"]+)".*/\1/p' "$app_gradle" | head -1)
if [ -z "$package_name" ]; then package_name=$(sed -nE 's/.*applicationId = "([^"]+)".*/\1/p' "$app_gradle" | head -1); fi
version_name=$(sed -nE 's/.*versionName = "([^"]+)".*/\1/p' "$app_gradle" | head -1)
version_code=$(sed -nE 's/.*versionCode = ([0-9]+).*/\1/p' "$app_gradle" | head -1)
if [ -z "$package_name" ]; then package_name=unknown; fi
if [ -z "$version_name" ]; then version_name=unknown; fi
if [ -z "$version_code" ]; then version_code=0; fi

schema_version=$(find "$root/database/schema" -type f -printf '%f\n' 2>/dev/null | sed -nE 's/^([0-9]+).*/\1/p' | sort -n | tail -1 || true)
if [ -z "$schema_version" ]; then schema_version=unknown; fi

catalog_rows=$(printenv MOVIA_CATALOG_ROWS || true)
if [ -z "$catalog_rows" ] && [ -n "$db_path" ] && [ -f "$db_path" ] && command -v sqlite3 >/dev/null 2>&1; then
  for table_name in media_content mediaContent catalog media_items; do
    exists=$(sqlite3 "$db_path" "SELECT 1 FROM sqlite_master WHERE type='table' AND name='$table_name' LIMIT 1;" 2>/dev/null || true)
    if [ "$exists" = "1" ]; then
      catalog_rows=$(sqlite3 "$db_path" "SELECT COUNT(*) FROM \"$table_name\";" 2>/dev/null || true)
      break
    fi
  done
fi
if [ -z "$catalog_rows" ]; then catalog_rows=unknown; fi

git_commit=$(git -C "$root" rev-parse HEAD 2>/dev/null || true)
if [ -z "$git_commit" ]; then git_commit=unknown; fi
git_dirty_count=$(git -C "$root" status --porcelain 2>/dev/null | wc -l | tr -d ' ')

installed_apk_path=
installed_version_name=
installed_version_code=
if command -v rish >/dev/null 2>&1 && [ "$package_name" != unknown ]; then
  installed_apk_path=$(rish -c "pm path $package_name" 2>/dev/null | sed -n 's/^package://p' | head -1 || true)
  installed_version_name=$(rish -c "dumpsys package $package_name" 2>/dev/null | sed -nE 's/.*versionName=([^, ]+).*/\1/p' | head -1 || true)
  installed_version_code=$(rish -c "dumpsys package $package_name" 2>/dev/null | sed -nE 's/.*versionCode=([0-9]+).*/\1/p' | head -1 || true)
fi

effective_apk_path=$apk_path
apk_sha256=
apk_size=
apk_name=
if [ -n "$apk_path" ] && [ -f "$apk_path" ]; then
  apk_sha256=$(sha256sum "$apk_path" | awk '{print $1}')
  apk_size=$(stat -c '%s' -- "$apk_path")
  apk_name=$(basename -- "$apk_path")
elif [ -n "$installed_apk_path" ] && command -v rish >/dev/null 2>&1; then
  effective_apk_path=$installed_apk_path
  apk_sha256=$(rish -c "sha256sum $installed_apk_path" 2>/dev/null | awk '{print $1}' || true)
  apk_size=$(rish -c "stat -c '%s' $installed_apk_path" 2>/dev/null || true)
  apk_name=$(basename -- "$installed_apk_path")
fi

db_sha256=
db_size=
if [ -n "$db_path" ] && [ -f "$db_path" ]; then
  db_sha256=$(sha256sum "$db_path" | awk '{print $1}')
  db_size=$(stat -c '%s' -- "$db_path")
fi

backend_verification=not_found
if find "$root/backend" -type f \( -name '*.py' -o -name '*.js' -o -name '*.ts' \) -print -quit 2>/dev/null | grep -q .; then
  backend_verification=source_present_not_verified
fi
agent_verification=not_found
if find "$root/agent" -type f \( -name '*.py' -o -name '*.js' -o -name '*.ts' \) -print -quit 2>/dev/null | grep -q .; then
  agent_verification=source_present_not_verified
fi
build_verification=available_not_run
if [ ! -x "$root/android/gradlew" ]; then
  build_verification=fail:gradle_wrapper_unavailable
else
  sdk_path=$(printenv ANDROID_HOME || true)
  if [ -z "$sdk_path" ]; then sdk_path=$(printenv ANDROID_SDK_ROOT || true); fi
  if [ -z "$sdk_path" ] && [ -f "$root/android/local.properties" ]; then
    sdk_path=$(sed -nE 's/^sdk.dir=(.*)$/\1/p' "$root/android/local.properties" | head -1)
  fi
  if [ -z "$sdk_path" ] || [ ! -d "$sdk_path" ]; then
    build_verification=fail:sdk_missing
  fi
fi
playback_verification=not_verified
if [ -f "$root/docs/playback/KNOWN_ISSUES.md" ]; then playback_verification=not_verified_known_issues; fi

recovered_path=$(readlink -f "$root/android/recovered-jadx" 2>/dev/null || true)
recovered_bytes=0
recovered_files=0
if [ -d "$recovered_path" ]; then
  recovered_bytes=$(du -sb "$recovered_path" 2>/dev/null | awk '{print $1}')
  recovered_files=$(find "$recovered_path" -type f 2>/dev/null | wc -l | tr -d ' ')
fi

stage_dir="$root/release/.staging"
if [ -n "$apk_path" ] && [ -f "$apk_path" ]; then
  mkdir -p "$stage_dir"
  staged_apk="$stage_dir/Movia-$version_name-code$version_code.apk"
  cp "$apk_path" "$staged_apk"
  sha256sum "$staged_apk" > "$stage_dir/SHA256SUMS.txt"
fi
if [ "$make_db_snapshot" -eq 1 ] && [ -n "$db_path" ] && [ -f "$db_path" ]; then
  mkdir -p "$stage_dir"
  gzip -c "$db_path" > "$stage_dir/catalog-$version_name-code$version_code.db.gz"
  if [ -f "$stage_dir/SHA256SUMS.txt" ]; then
    sha256sum "$stage_dir/catalog-$version_name-code$version_code.db.gz" >> "$stage_dir/SHA256SUMS.txt"
  else
    sha256sum "$stage_dir/catalog-$version_name-code$version_code.db.gz" > "$stage_dir/SHA256SUMS.txt"
  fi
fi

export MOVIA_CANONICAL_ROOT="$root"
export MOVIA_PACKAGE="$package_name"
export MOVIA_VERSION_NAME="$version_name"
export MOVIA_VERSION_CODE="$version_code"
export MOVIA_CATALOG_ROWS="$catalog_rows"
export MOVIA_SCHEMA_VERSION="$schema_version"
export MOVIA_APK_PATH="$effective_apk_path"
export MOVIA_APK_NAME="$apk_name"
export MOVIA_APK_SHA256="$apk_sha256"
export MOVIA_APK_SIZE="$apk_size"
export MOVIA_INSTALLED_APK_PATH="$installed_apk_path"
export MOVIA_INSTALLED_VERSION_NAME="$installed_version_name"
export MOVIA_INSTALLED_VERSION_CODE="$installed_version_code"
export MOVIA_CATALOG_DB_PATH="$db_path"
export MOVIA_CATALOG_DB_SHA256="$db_sha256"
export MOVIA_CATALOG_DB_SIZE="$db_size"
export MOVIA_BACKEND_VERIFICATION="$backend_verification"
export MOVIA_AGENT_VERIFICATION="$agent_verification"
export MOVIA_BUILD_VERIFICATION="$build_verification"
export MOVIA_PLAYBACK_VERIFICATION="$playback_verification"
export MOVIA_GIT_COMMIT="$git_commit"
export MOVIA_GIT_DIRTY_COUNT="$git_dirty_count"
export MOVIA_RECOVERED_PATH="$recovered_path"
export MOVIA_RECOVERED_BYTES="$recovered_bytes"
export MOVIA_RECOVERED_FILES="$recovered_files"

node <<'NODE'
const fs = require("fs");
const path = require("path");
const env = process.env;
const root = env.MOVIA_CANONICAL_ROOT;
const nullable = value => value === "" || value === "unknown" ? null : value;
const numberOrNull = value => {
  if (value === "" || value === "unknown") return null;
  const parsed = Number(value);
  return Number.isFinite(parsed) ? parsed : value;
};

const baseline = {
  project: "Movia",
  package: nullable(env.MOVIA_PACKAGE),
  versionName: nullable(env.MOVIA_VERSION_NAME),
  versionCode: numberOrNull(env.MOVIA_VERSION_CODE),
  apkPath: nullable(env.MOVIA_APK_PATH),
  apkName: nullable(env.MOVIA_APK_NAME),
  apkSha256: nullable(env.MOVIA_APK_SHA256),
  apkSizeBytes: numberOrNull(env.MOVIA_APK_SIZE),
  catalogRows: numberOrNull(env.MOVIA_CATALOG_ROWS),
  catalogSchemaVersion: nullable(env.MOVIA_SCHEMA_VERSION),
  catalogDbPath: nullable(env.MOVIA_CATALOG_DB_PATH),
  catalogDbSha256: nullable(env.MOVIA_CATALOG_DB_SHA256),
  catalogDbSizeBytes: numberOrNull(env.MOVIA_CATALOG_DB_SIZE),
  installed: {
    package: nullable(env.MOVIA_PACKAGE),
    apkPath: nullable(env.MOVIA_INSTALLED_APK_PATH),
    versionName: nullable(env.MOVIA_INSTALLED_VERSION_NAME),
    versionCode: numberOrNull(env.MOVIA_INSTALLED_VERSION_CODE)
  },
  backendService: nullable(env.MOVIA_BACKEND_SERVICE || "not-found"),
  agentSchemaVersion: null,
  gitCommit: nullable(env.MOVIA_GIT_COMMIT),
  gitDirtyFiles: Number(env.MOVIA_GIT_DIRTY_COUNT || 0),
  createdAt: new Date().toISOString(),
  verification: {
    build: env.MOVIA_BUILD_VERIFICATION,
    backend: env.MOVIA_BACKEND_VERIFICATION,
    agent: env.MOVIA_AGENT_VERIFICATION,
    playback: env.MOVIA_PLAYBACK_VERIFICATION
  }
};

const assets = {
  project: "Movia",
  canonicalRoot: root,
  repository: "https://github.com/KatiSim/Movia",
  androidSource: path.join(root, "android"),
  recoveredJadx: nullable(env.MOVIA_RECOVERED_PATH),
  recoveredJadxBytes: numberOrNull(env.MOVIA_RECOVERED_BYTES),
  recoveredJadxFiles: numberOrNull(env.MOVIA_RECOVERED_FILES),
  backend: {
    status: env.MOVIA_BACKEND_VERIFICATION,
    path: path.join(root, "backend")
  },
  agent: {
    status: env.MOVIA_AGENT_VERIFICATION,
    path: path.join(root, "agent")
  },
  catalogDb: {
    status: env.MOVIA_CATALOG_DB_PATH ? "path-supplied" : "not-found",
    path: nullable(env.MOVIA_CATALOG_DB_PATH),
    sha256: nullable(env.MOVIA_CATALOG_DB_SHA256)
  },
  installed: baseline.installed,
  generatedAt: baseline.createdAt
};

fs.writeFileSync(path.join(root, "CURRENT_BASELINE.json"), JSON.stringify(baseline, null, 2) + "\n");
fs.writeFileSync(path.join(root, "reference", "CURRENT_PHONE_ASSETS.json"), JSON.stringify(assets, null, 2) + "\n");
console.log("BASELINE_WRITTEN " + path.join(root, "CURRENT_BASELINE.json"));
NODE
