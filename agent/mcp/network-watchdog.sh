#!/data/data/com.termux/files/usr/bin/bash
while true; do
  if ! curl -s -m 2 http://127.0.0.1:8940/healthz > /dev/null; then
    sv restart jarvis-stable-core 2>/dev/null || true
  fi

  if ping -c 1 -W 2 1.1.1.1 > /dev/null 2>&1; then
    if ! sv status jarvis-stable-funnel | grep -q "^run:"; then
      sv up jarvis-stable-funnel 2>/dev/null || true
    fi
  fi
  sleep 5
done
