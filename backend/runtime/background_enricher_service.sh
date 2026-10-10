#!/data/data/com.termux/files/usr/bin/bash
set -u
exec 2>&1
export PYTHONUNBUFFERED=1
export MOVIA_ENABLE_HDREZKA_PROVIDER_CONTRACT=1
export MOVIA_ENABLE_ZONA_MOBI_PROVIDER_CONTRACT=0
export SSL_CERT_FILE="$PREFIX/etc/tls/cert.pem"
export MOVIA_BACKGROUND_BULK=1
export MOVIA_BACKGROUND_REQUIRE_CHARGING=0
export MOVIA_BACKGROUND_MONTHLY_GIB="${MOVIA_BACKGROUND_MONTHLY_GIB:-4.2}"
export MOVIA_BACKGROUND_DAILY_MIB=0
export MOVIA_BACKGROUND_TORRENT_LOOKUP=1
export MOVIA_ENABLE_TORRENT_PROVIDER_CONTRACT=1
MOVIA_WIFI_ENRICH_WORKERS="${MOVIA_WIFI_ENRICH_WORKERS:-6}"
MOVIA_MOBILE_ENRICH_WORKERS="${MOVIA_MOBILE_ENRICH_WORKERS:-1}"
MOVIA_MOBILE_ENRICH_LIMIT="${MOVIA_MOBILE_ENRICH_LIMIT:-20}"
MOVIA_WIFI_VERIFY_LIMIT="${MOVIA_WIFI_VERIFY_LIMIT:-50}"
MOVIA_MOBILE_VERIFY_LIMIT="${MOVIA_MOBILE_VERIFY_LIMIT:-10}"
MOVIA_WIFI_METADATA_AUDIT_LIMIT="${MOVIA_WIFI_METADATA_AUDIT_LIMIT:-100}"
MOVIA_MOBILE_METADATA_AUDIT_LIMIT="${MOVIA_MOBILE_METADATA_AUDIT_LIMIT:-10}"
MOVIA_WIFI_INTERVAL_SECONDS="${MOVIA_WIFI_INTERVAL_SECONDS:-300}"
MOVIA_MOBILE_INTERVAL_SECONDS="${MOVIA_MOBILE_INTERVAL_SECONDS:-1800}"
MOVIA_BLOCKED_RECHECK_SECONDS="${MOVIA_BLOCKED_RECHECK_SECONDS:-1800}"
cd "$HOME/projects/media-parser"

child_pid=""
run_child() {
  "$@" &
  child_pid=$!
  wait "$child_pid" 2>/dev/null || true
  child_pid=""
}
stop_children() {
  if [ -n "$child_pid" ]; then
    kill -TERM "$child_pid" 2>/dev/null || true
    wait "$child_pid" 2>/dev/null || true
    child_pid=""
  fi
  exit 0
}
trap stop_children TERM INT HUP

while true; do
  sleep_seconds="$MOVIA_BLOCKED_RECHECK_SECONDS"
  mode="$("$PREFIX/bin/python3" -u background_network_budget.py \
    --allow-metered --no-charging-required \
    --monthly-gib "$MOVIA_BACKGROUND_MONTHLY_GIB" --daily-mib 0 --mode-only 2>/dev/null || true)"

  case "$mode" in
    wifi)
      export MOVIA_BACKGROUND_ALLOW_METERED=1
      # Systematic catalog identity repair runs before stream discovery so the
      # filler never wastes provider requests on a known wrong movie/tv type.
      run_child "$PREFIX/bin/python3" -u metadata_repair.py --audit-media-type \
        --limit "$MOVIA_WIFI_METADATA_AUDIT_LIMIT" --workers 4 --resume
      export MOVIA_ENRICH_WORKERS="$MOVIA_WIFI_ENRICH_WORKERS"
      run_child "$PREFIX/bin/python3" -u content_filler.py --all --resume
      "$PREFIX/bin/python3" -u playback_manifest_verify_batch.py \
        --limit "$MOVIA_WIFI_VERIFY_LIMIT" --workers 4 --timeout 2 || true
      sleep_seconds="$MOVIA_WIFI_INTERVAL_SECONDS"
      ;;
    mobile)
      export MOVIA_BACKGROUND_ALLOW_METERED=1
      run_child "$PREFIX/bin/python3" -u metadata_repair.py --audit-media-type \
        --limit "$MOVIA_MOBILE_METADATA_AUDIT_LIMIT" --workers 1 --resume
      export MOVIA_ENRICH_WORKERS="$MOVIA_MOBILE_ENRICH_WORKERS"
      run_child "$PREFIX/bin/python3" -u content_filler.py \
        --limit "$MOVIA_MOBILE_ENRICH_LIMIT" --resume
      "$PREFIX/bin/python3" -u playback_manifest_verify_batch.py \
        --limit "$MOVIA_MOBILE_VERIFY_LIMIT" --workers 1 --timeout 2 || true
      sleep_seconds="$MOVIA_MOBILE_INTERVAL_SECONDS"
      ;;
  esac

  sleep "$sleep_seconds" &
  child_pid=$!
  wait "$child_pid" 2>/dev/null || true
  child_pid=""
done
