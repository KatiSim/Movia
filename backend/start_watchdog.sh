#!/data/data/com.termux/files/usr/bin/bash
DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PID_FILE="$DIR/.watchdog.pid"

if [ -f "$PID_FILE" ] && kill -0 "$(cat "$PID_FILE")" >/dev/null 2>&1; then
    echo "✅ Watchdog harvester already running (PID: $(cat "$PID_FILE"))"
    python3 "$DIR/watchdog_harvester.py" --status
    exit 0
fi

nohup python3 "$DIR/watchdog_harvester.py" > "$DIR/watchdog.log" 2>&1 &
PID=$!
echo "$PID" > "$PID_FILE"
sleep 1

echo "🚀 Watchdog harvester started in background (PID: $PID)"
python3 "$DIR/watchdog_harvester.py" --status
