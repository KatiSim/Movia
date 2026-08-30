#!/data/data/com.termux/files/usr/bin/bash
set -u

backend_url=$(printenv MOVIA_BACKEND_HEALTH_URL || true)
if [ -z "$backend_url" ]; then backend_url=$(printenv MOVIA_BACKEND_URL || true); fi
if [ -z "$backend_url" ]; then backend_url=http://127.0.0.1:5001; fi
backend_url=$(printf '%s' "$backend_url" | sed 's:/*$::')

agent_url=$(printenv MOVIA_AGENT_HEALTH_URL || true)
if [ -z "$agent_url" ]; then agent_url=$(printenv MOVIA_AGENT_URL || true); fi
if [ -z "$agent_url" ]; then agent_url=http://127.0.0.1:8899; fi
agent_url=$(printf '%s' "$agent_url" | sed 's:/*$::')

failures=0
if command -v curl >/dev/null 2>&1 && curl --fail --silent --show-error --max-time 5 "$backend_url/health" >/dev/null 2>&1; then
  echo "backend: PASS"
else
  echo "backend: FAIL"
  failures=$((failures + 1))
fi
if command -v curl >/dev/null 2>&1 && curl --fail --silent --show-error --max-time 5 "$agent_url/health" >/dev/null 2>&1; then
  echo "agent: PASS"
else
  echo "agent: FAIL"
  failures=$((failures + 1))
fi
if [ "$failures" -ne 0 ]; then
  echo "FAIL"
  exit 1
fi
echo "PASS"
