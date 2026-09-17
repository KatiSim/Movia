#!/data/data/com.termux/files/usr/bin/bash
set -u
set -o pipefail

ROOT_DIR="$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
CLI="${MOVIA_CLI:-$HOME/bin/movia-agent}"

force_stop_ok=0
snapshot_rc=127
snapshot_succeeds=0
snapshot_valid=0
before_activity=""
after_stop_activity=""
after_snapshot_activity=""

read_current_activity() {
    local dump="" attempt
    for attempt in 1 2 3; do
        if dump="$(rish -c 'dumpsys activity activities' 2>/dev/null)"; then
            printf '%s\n' "$dump" | awk '
                /(topResumedActivity=|mResumedActivity|ResumedActivity:|mFocusedApp=|mFocusedApp:)/ {
                    for (i = 1; i <= NF; i++) {
                        if ($i ~ /^[A-Za-z0-9._]+\/[A-Za-z0-9._$]+/) {
                            gsub(/[}].*$/, "", $i)
                            print $i
                            exit
                        }
                    }
                }
            '
            return 0
        fi
        sleep 0.15
    done
    return 1
}

activity_is_movia() {
    case "$1" in
        *app.movia.android*) return 0 ;;
        *) return 1 ;;
    esac
}

if before_activity="$(read_current_activity)"; then
    before_activity_read=1
else
    before_activity_read=0
fi

if rish -c 'am force-stop app.movia.android' >/dev/null 2>&1; then
    force_stop_ok=1
fi

sleep 0.2
if after_stop_activity="$(read_current_activity)"; then
    after_stop_activity_read=1
else
    after_stop_activity_read=0
fi

if [ -x "$CLI" ] || [ -f "$CLI" ]; then
    snapshot_output=""
    if snapshot_output="$(bash "$CLI" snapshot 2>/dev/null)"; then
        snapshot_rc=0
        snapshot_succeeds=1
    else
        snapshot_rc=$?
    fi
    if [ "$snapshot_succeeds" -eq 1 ] && printf '%s' "$snapshot_output" | python3 -c '
import json
import sys

value = json.load(sys.stdin)
if not isinstance(value, dict) or "schemaVersion" not in value:
    raise SystemExit(1)
'; then
        snapshot_valid=1
    fi
fi

if after_snapshot_activity="$(read_current_activity)"; then
    after_snapshot_activity_read=1
else
    after_snapshot_activity_read=0
fi

after_stop_not_movia=0
if [ "$after_stop_activity_read" -eq 1 ] && ! activity_is_movia "$after_stop_activity"; then
    after_stop_not_movia=1
fi

after_snapshot_not_movia=0
if [ "$after_snapshot_activity_read" -eq 1 ] && ! activity_is_movia "$after_snapshot_activity"; then
    after_snapshot_not_movia=1
fi

check_pass=0
check_fail=0
print_check() {
    local name="$1"
    local value="$2"
    if [ "$value" -eq 1 ]; then
        printf 'PASS %s\n' "$name"
        check_pass=$((check_pass + 1))
    else
        printf 'FAIL %s\n' "$name"
        check_fail=$((check_fail + 1))
    fi
}

print_check "rish force-stop app.movia.android" "$force_stop_ok"
print_check "current activity readable before force-stop" "$before_activity_read"
print_check "current activity is not Movia after force-stop" "$after_stop_not_movia"
print_check "cli snapshot succeeds after force-stop" "$snapshot_succeeds"
print_check "snapshot is a schema-bearing JSON object" "$snapshot_valid"
print_check "current activity readable after snapshot" "$after_snapshot_activity_read"
print_check "snapshot did not open Movia Activity" "$after_snapshot_not_movia"

overall=0
if [ "$force_stop_ok" -eq 1 ] \
    && [ "$before_activity_read" -eq 1 ] \
    && [ "$after_stop_not_movia" -eq 1 ] \
    && [ "$snapshot_succeeds" -eq 1 ] \
    && [ "$snapshot_valid" -eq 1 ] \
    && [ "$after_snapshot_activity_read" -eq 1 ] \
    && [ "$after_snapshot_not_movia" -eq 1 ]; then
    overall=1
fi

if [ "$overall" -eq 1 ]; then
    pass_json=true
else
    pass_json=false
fi
if [ "$before_activity_read" -eq 1 ] && activity_is_movia "$before_activity"; then
    before_was_movia=true
else
    before_was_movia=false
fi
if [ "$force_stop_ok" -eq 1 ]; then force_stop_json=true; else force_stop_json=false; fi
if [ "$before_activity_read" -eq 1 ]; then before_read_json=true; else before_read_json=false; fi
if [ "$after_stop_activity_read" -eq 1 ]; then after_stop_read_json=true; else after_stop_read_json=false; fi
if [ "$after_stop_not_movia" -eq 1 ]; then after_stop_not_movia_json=true; else after_stop_not_movia_json=false; fi
if [ "$after_snapshot_activity_read" -eq 1 ]; then after_snapshot_read_json=true; else after_snapshot_read_json=false; fi
if [ "$after_snapshot_not_movia" -eq 1 ]; then after_snapshot_not_movia_json=true; else after_snapshot_not_movia_json=false; fi

printf '{"script":"01_headless_cold","pass":%s,"checksPassed":%d,"checksFailed":%d,"forceStop":%s,"snapshotExit":%d,"beforeActivityRead":%s,"beforeActivityWasMovia":%s,"afterStopActivityRead":%s,"afterStopActivityNotMovia":%s,"afterSnapshotActivityRead":%s,"afterSnapshotActivityNotMovia":%s}\n' \
    "$pass_json" "$check_pass" "$check_fail" "$force_stop_json" "$snapshot_rc" \
    "$before_read_json" "$before_was_movia" "$after_stop_read_json" \
    "$after_stop_not_movia_json" "$after_snapshot_read_json" "$after_snapshot_not_movia_json"

if [ "$overall" -eq 1 ]; then
    exit 0
fi
exit 1
