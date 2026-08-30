#!/data/data/com.termux/files/usr/bin/bash
set -eu
script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
"$script_dir/restore-check.sh"
echo "Restore prerequisites: PASS"
echo "Follow RESTORE.md for the non-destructive restore sequence."
