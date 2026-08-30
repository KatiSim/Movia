#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail
ROOT="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
bash "$ROOT/scripts/restore-check.sh"
echo "Restore prerequisites are present. Follow RESTORE.md for DB, secrets, services, build and install."
