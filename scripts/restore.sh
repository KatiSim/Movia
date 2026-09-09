#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail
ROOT="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
mode="${1:-full}"
case "$mode" in
  app-only)
    exec bash "$ROOT/scripts/install.sh"
    ;;
  full)
    bash "$ROOT/scripts/restore-check.sh"
    command -v python3 >/dev/null 2>&1 || { echo "Install Python first; see RESTORE.md"; exit 1; }
    python3 -m pip install -r "$ROOT/backend/requirements.txt"
    if command -v npm >/dev/null 2>&1; then
      (cd "$ROOT/agent/mcp" && npm ci && npm run build)
    else
      echo "NOTE: npm unavailable; MCP dependencies were not restored."
    fi
    bash "$ROOT/scripts/setup-local-runtime.sh"
    echo
    echo "Runtime restored. Add private backend/MCP credentials as described in SECRETS_SETUP.md."
    echo "Then start services and install the exact APK:"
    echo "  sv up movia-torrserver movia-media-parser movia-stream-enricher movia-stream-enricher-log movia-cache-pruner"
    echo "  bash scripts/install.sh"
    echo "  bash scripts/health-check.sh --full --package"
    ;;
  *) echo "Usage: scripts/restore.sh [full|app-only]"; exit 2 ;;
esac
