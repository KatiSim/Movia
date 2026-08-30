#!/data/data/com.termux/files/usr/bin/bash
DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TORRENT_CACHE="$DIR/torrent_cache"
mkdir -p "$TORRENT_CACHE"

# 1. Запуск единственного aria2c RPC daemon на порту 6800.
# Проверяем сам RPC socket, а не строку процесса: daemon запускается из aria2.conf
# и его argv не обязан содержать номер порта.
if ! curl -sS --max-time 1 http://127.0.0.1:6800/jsonrpc >/dev/null 2>&1; then
    echo "Starting aria2c RPC daemon on port 6800..."
    aria2c --conf-path="$HOME/.aria2/aria2.conf" -D
fi

# 2. Запуск streamer.py (P2P on-demand streaming daemon на порту 8888)
if ! curl -s http://127.0.0.1:8888/health > /dev/null 2>&1; then
    echo "Starting Movia Streamer daemon on port 8888..."
    pkill -f "python3.*streamer\.py" 2>/dev/null || true
    sleep 0.5
    nohup setsid python3 -u "$DIR/streamer.py" > "$DIR/streamer.log" 2>&1 &
fi

sleep 1
if curl -s http://127.0.0.1:8888/health > /dev/null 2>&1; then
    echo "✅ Movia backend services are up and running (aria2c :6800, streamer :8888)"
else
    echo "⚠️ Waiting for streamer socket on http://127.0.0.1:8888..."
fi
