#!/data/data/com.termux/files/usr/bin/bash
DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PID_FILE="$DIR/.streamer.pid"

if python3 "$DIR/streamer.py" --check >/dev/null 2>&1; then
    echo "✅ Streamer is already running on http://127.0.0.1:8888"
    exit 0
fi

nohup python3 "$DIR/streamer.py" > "$DIR/streamer.log" 2>&1 &
PID=$!
disown $PID
echo "$PID" > "$PID_FILE"
sleep 0.5

if python3 "$DIR/streamer.py" --check >/dev/null 2>&1; then
    echo "🚀 Movia P2P Streamer successfully started (PID: $PID) on http://127.0.0.1:8888"
else
    echo "⚠️ Streamer process started with PID $PID, waiting for socket..."
fi
