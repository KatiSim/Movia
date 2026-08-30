#!/usr/bin/env python3
import time
import subprocess
import json
import urllib.request
import re
import sys
import threading

def log_event(category, message):
    ts = time.strftime("%H:%M:%S")
    print(f"[{ts}] [{category}] {message}", flush=True)

def monitor_aria2():
    last_gids = set()
    while True:
        try:
            rpc_url = "http://127.0.0.1:6800/jsonrpc"
            payload = json.dumps({
                "jsonrpc": "2.0",
                "id": "live_tracker",
                "method": "aria2.tellActive",
                "params": ["token:movia_secret", ["gid", "status", "totalLength", "completedLength", "downloadSpeed", "numSeeders", "connections", "files"]]
            }).encode("utf-8")
            req = urllib.request.Request(rpc_url, data=payload, headers={"Content-Type": "application/json"})
            with urllib.request.urlopen(req, timeout=1.5) as resp:
                data = json.loads(resp.read().decode("utf-8"))
                tasks = data.get("result", [])
                for t in tasks:
                    gid = t.get("gid")
                    speed = int(t.get("downloadSpeed", 0)) / 1024
                    completed = int(t.get("completedLength", 0)) / (1024 * 1024)
                    total = int(t.get("totalLength", 0)) / (1024 * 1024)
                    seeders = t.get("numSeeders", 0)
                    conns = t.get("connections", 0)
                    files = t.get("files", [])
                    file_name = files[0].get("path", "").split("/")[-1] if files else "metadata"

                    if gid not in last_gids:
                        last_gids.add(gid)
                        log_event("ARIA2-NEW", f"Task {gid} started: '{file_name}' | Conns: {conns} | Seeders: {seeders}")
                    elif speed > 0 or completed > 0:
                        log_event("ARIA2-PROG", f"Task {gid}: {completed:.1f}/{total:.1f}MB ({speed:.1f} KB/s) | Seeds: {seeders} | File: {file_name}")
        except Exception:
            pass
        time.sleep(2)

def monitor_logcat():
    log_event("SYSTEM", "Starting Logcat real-time listener...")
    while True:
        try:
            proc = subprocess.Popen(
                ["rish", "-c", "logcat -v time | grep -iE 'MoviaStreamDebug|MoviaPlayer|PlaybackSession|ExoPlayer|127.0.0.1'"],
                stdout=subprocess.PIPE,
                stderr=subprocess.STDOUT,
                text=True,
                bufsize=1
            )
            for line in iter(proc.stdout.readline, ''):
                clean = line.strip()
                if not clean:
                    continue
                if "MoviaStreamDebug" in clean:
                    msg = clean.split("MoviaStreamDebug:")[-1].strip() if "MoviaStreamDebug:" in clean else clean
                    log_event("APP-STREAM", msg)
                elif "PlaybackSession" in clean:
                    msg = clean.split("PlaybackSession:")[-1].strip() if "PlaybackSession:" in clean else clean
                    log_event("APP-SESSION", msg)
                elif "ExoPlayer" in clean:
                    if any(k in clean for k in ["state", "error", "prepare", "seek", "track"]):
                        log_event("EXOPLAYER", clean[-120:])
                elif not clean.startswith("Request timeout"):
                    log_event("LOGCAT", clean[-120:])
        except Exception as e:
            log_event("ERROR", f"Logcat monitor error: {e}")
        time.sleep(5)

if __name__ == "__main__":
    print("=" * 60, flush=True)
    log_event("SYSTEM", "🚀 Movia Real-Time Monitor Started")
    print("=" * 60, flush=True)

    t_aria = threading.Thread(target=monitor_aria2, daemon=True)
    t_aria.start()

    t_logcat = threading.Thread(target=monitor_logcat, daemon=True)
    t_logcat.start()

    while True:
        time.sleep(1)
