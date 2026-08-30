#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

PROJECT="$HOME/termux-mcp"
CONFIG_DIR="$HOME/.config/termux-mcp"
SECRET_FILE="$CONFIG_DIR/secret"

cd "$PROJECT"
umask 077
mkdir -p "$CONFIG_DIR" "$HOME/.termux-mcp/jobs"

if [ ! -s "$SECRET_FILE" ]; then
  od -An -N32 -tx1 /dev/urandom | tr -d ' \\n' > "$SECRET_FILE"
  chmod 600 "$SECRET_FILE"
fi

export TERMUX_MCP_HOST="${TERMUX_MCP_HOST:-127.0.0.1}"
export TERMUX_MCP_PORT="${TERMUX_MCP_PORT:-8940}"
export TERMUX_MCP_ROOTS="${TERMUX_MCP_ROOTS:-$HOME:/storage/emulated/0}"
export TERMUX_MCP_JOB_ROOT="${TERMUX_MCP_JOB_ROOT:-$HOME/.termux-mcp/jobs}"
export TERMUX_MCP_SECRET="${TERMUX_MCP_SECRET:-$(cat "$SECRET_FILE")}"

termux-wake-lock 2>/dev/null || true

exec node dist/server.js
