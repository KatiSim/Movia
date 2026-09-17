#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail
umask 077
TOKEN_FILE="${MOVIA_TOKEN_FILE:-$HOME/.config/movia-agent/token}"
if [ ! -s "$TOKEN_FILE" ]; then
    echo "Movia agent token file is missing: $TOKEN_FILE" >&2
    exit 2
fi
TOKEN="$(tr -d '\r\n' < "$TOKEN_FILE")"
if [[ ! "$TOKEN" =~ ^[0-9a-fA-F]{64}$ ]]; then
    echo "Movia agent token has invalid shape" >&2
    exit 2
fi
TOKEN="${TOKEN,,}"
if ! command -v rish >/dev/null 2>&1; then
    echo "rish is required only for initial token provisioning/recovery" >&2
    exit 3
fi
printf '%s' "$TOKEN" | rish -c 'run-as app.movia.android sh -c "umask 077; mkdir -p files/agent; cat > files/agent/movia-agent.token; chmod 700 files/agent; chmod 600 files/agent/movia-agent.token"' >/dev/null 2>&1
LOCAL_HASH="$(printf '%s' "$TOKEN" | sha256sum | awk '{print $1}')"
APP_HASH="$(rish -c 'run-as app.movia.android sh -c "sha256sum files/agent/movia-agent.token"' 2>&1 | awk '/movia-agent.token/{print $1}' | tail -1)"
if [ "$LOCAL_HASH" != "$APP_HASH" ]; then
    echo "Movia agent token provisioning verification failed" >&2
    exit 4
fi
echo "Movia agent token provisioned and verified"
