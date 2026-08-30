#!/usr/bin/env python3
import os
import sys
import time
import json
import sqlite3
import signal
import subprocess
from datetime import datetime
from pathlib import Path

BASE_DIR = Path("/data/data/com.termux/files/home")
MEDIA_PARSER_DIR = BASE_DIR / "projects/media-parser"
PROJECT_DIR = BASE_DIR / "projects/viora"
DB_PATH = MEDIA_PARSER_DIR / "catalog.db"
STATE_PATH = MEDIA_PARSER_DIR / "harvester_state.json"
STATUS_PATH = MEDIA_PARSER_DIR / "harvester_status.json"
WATCHDOG_PID_FILE = MEDIA_PARSER_DIR / ".watchdog.pid"
HARVESTER_SCRIPT = MEDIA_PARSER_DIR / "auto_harvester_60k.py"
MASTER_PIPELINE = PROJECT_DIR / "master_pipeline.py"
CANONICAL_JSON = BASE_DIR / "MoviaApp/Movia/CURRENT_CANONICAL.json"

TARGET_INTERMEDIATE = 40000
TARGET_FINAL = 60000

def get_db_stats():
    if not DB_PATH.exists():
        return 0, []
    try:
        conn = sqlite3.connect(str(DB_PATH), timeout=10)
        c = conn.cursor()
        total = c.execute("SELECT count(*) FROM movies;").fetchone()[0]
        cats = c.execute("SELECT count(*), category FROM movies GROUP BY category;").fetchall()
        conn.close()
        return total, cats
    except Exception:
        return 0, []

def get_process_pid(proc_name: str) -> list:
    try:
        res = subprocess.run(["pgrep", "-f", proc_name], capture_output=True, text=True)
        my_pid = os.getpid()
        return [int(p) for p in res.stdout.strip().split() if p.isdigit() and int(p) != my_pid]
    except Exception:
        return []

def is_process_running(proc_name: str) -> bool:
    return len(get_process_pid(proc_name)) > 0

def start_harvester():
    print(f"[{datetime.now().strftime('%Y-%m-%d %H:%M:%S')}] 🚀 Запуск auto_harvester_60k.py в фоновом режиме...")
    log_file = open(MEDIA_PARSER_DIR / "harvester.log", "a")
    proc = subprocess.Popen(
        [sys.executable, "-u", str(HARVESTER_SCRIPT)],
        stdout=log_file,
        stderr=subprocess.STDOUT,
        cwd=str(MEDIA_PARSER_DIR),
        start_new_session=True
    )
    return proc.pid

def stop_harvester():
    pids = get_process_pid("auto_harvester_60k.py")
    for pid in pids:
        try:
            os.kill(pid, signal.SIGTERM)
            print(f"[{datetime.now().strftime('%Y-%m-%d %H:%M:%S')}] 🛑 Остановлен процесс харвестера (PID: {pid})")
        except OSError:
            pass

def trigger_release_pipeline(milestone_name: str):
    print("\n" + "=" * 70)
    print(f"🎉 ДОСТИГНУТ МАЙЛСТОУН: {milestone_name}!")
    print(f"[{datetime.now().strftime('%Y-%m-%d %H:%M:%S')}] 🚀 Автоматический запуск master_pipeline.py...")
    print("=" * 70 + "\n")
    try:
        res = subprocess.run([sys.executable, str(MASTER_PIPELINE)], cwd=str(PROJECT_DIR), check=True)
        print(f"[{datetime.now().strftime('%Y-%m-%d %H:%M:%S')}] ✅ Релиз для {milestone_name} успешно собран и зафиксирован!")
    except Exception as e:
        print(f"[{datetime.now().strftime('%Y-%m-%d %H:%M:%S')}] ⚠️ Ошибка при выполнении master_pipeline.py: {e}")

def print_status_report():
    total, cats = get_db_stats()
    harvester_pids = get_process_pid("auto_harvester_60k.py")
    streamer_pids = get_process_pid("streamer.py")
    watchdog_pids = get_process_pid("watchdog_harvester.py")

    harvester_status = f"🟢 АКТИВЕН (PID: {harvester_pids[0]})" if harvester_pids else "🔴 ОСТАНОВЛЕН"
    streamer_status = f"🟢 АКТИВЕН (PID: {streamer_pids[0]})" if streamer_pids else "🔴 ОСТАНОВЛЕН"
    watchdog_status = f"🟢 АКТИВЕН (PID: {watchdog_pids[0]})" if watchdog_pids else "🔴 ОСТАНОВЛЕН"

    rate_text = "расчет..."
    eta_40k = "N/A"
    eta_60k = "N/A"

    if STATUS_PATH.exists():
        try:
            status_data = json.loads(STATUS_PATH.read_text(encoding="utf-8"))
            rate = status_data.get("rate_per_hour", 0)
            if rate > 0:
                rate_text = f"~{rate:,.0f} тайтлов/час"
                rem_40k = max(0, TARGET_INTERMEDIATE - total)
                rem_60k = max(0, TARGET_FINAL - total)
                eta_40k = f"{rem_40k / rate * 60:.1f} мин." if rem_40k > 0 else "ДОСТИГНУТ ✅"
                eta_60k = f"{rem_60k / rate:.1f} час." if rem_60k > 0 else "ДОСТИГНУТ 🎉"
        except Exception:
            pass

    current_canon = {}
    if CANONICAL_JSON.exists():
        try:
            current_canon = json.loads(CANONICAL_JSON.read_text(encoding="utf-8"))
        except Exception:
            pass

    print("\n" + "=" * 65)
    print("        📊 ТЕКУЩИЙ СТАТУС ДИСПЕТЧЕРА И КАТАЛОГА MOVIA")
    print("=" * 65)
    print(f"📦 Всего записей в SQLite:     {total:,} / {TARGET_FINAL:,} ({total/TARGET_FINAL*100:.1f}%)")
    print("-----------------------------------------------------------------")
    for cnt, cat in sorted(cats, key=lambda x: x[0], reverse=True):
        print(f"   • {cat.ljust(16)}: {cnt:,}")
    print("-----------------------------------------------------------------")
    print(f"⚡ Скорость сбора:             {rate_text}")
    print(f"⏳ Расчетное время до 40k:     {eta_40k}")
    print(f"🏁 Расчетное время до 60k:     {eta_60k}")
    print("-----------------------------------------------------------------")
    print(f"🤖 Харвестер (TMDB/60k):       {harvester_status}")
    print(f"🎬 Стример (127.0.0.1:8888):   {streamer_status}")
    print(f"🛡️ Диспетчер (Watchdog):       {watchdog_status}")
    print("-----------------------------------------------------------------")
    print(f"🏛️ Активный канонический релиз: v{current_canon.get('versionName', '0.3.75')} (code {current_canon.get('versionCode', '---')})")
    print(f"🕒 Время обновления:           {datetime.now().strftime('%Y-%m-%d %H:%M:%S')}")
    print("=" * 65 + "\n")

def run_watchdog_loop():
    print("=" * 70)
    print("   🛡️ ЗАПУСК АВТОНОМНОГО ДИСПЕТЧЕРА СБОРА И РЕЛИЗОВ (WATCHDOG 60K)")
    print("=" * 70)
    WATCHDOG_PID_FILE.write_text(str(os.getpid()))

    def shutdown(signum, frame):
        print("\nЗавершение работы Watchdog...")
        if WATCHDOG_PID_FILE.exists():
            try:
                WATCHDOG_PID_FILE.unlink()
            except OSError:
                pass
        sys.exit(0)

    signal.signal(signal.SIGINT, shutdown)
    signal.signal(signal.SIGTERM, shutdown)

    history_points = []
    initial_count, _ = get_db_stats()
    history_points.append((time.time(), initial_count))

    triggered_40k = initial_count >= TARGET_INTERMEDIATE
    triggered_60k = initial_count >= TARGET_FINAL

    while True:
        try:
            total_items, cats = get_db_stats()
            now = time.time()
            history_points.append((now, total_items))
            # Сохраняем историю за последние 15 минут
            history_points = [p for p in history_points if (now - p[0]) <= 900]

            # 1. Проверяем состояние харвестера
            if total_items < TARGET_FINAL:
                if not is_process_running("auto_harvester_60k.py"):
                    print(f"[{datetime.now().strftime('%H:%M:%S')}] ⚠️ Харвестер не обнаружен среди процессов! Перезапуск...")
                    start_harvester()
                    time.sleep(2)
            else:
                if is_process_running("auto_harvester_60k.py"):
                    print(f"[{datetime.now().strftime('%H:%M:%S')}] 🎉 Цель в 60 000+ тайтлов достигнута! Остановка сбора...")
                    stop_harvester()

            # 2. Расчет скользящей скорости сбора (тайтлов в час)
            rate_per_hour = 0
            if len(history_points) >= 2:
                dt_hours = (history_points[-1][0] - history_points[0][0]) / 3600.0
                dn = history_points[-1][1] - history_points[0][1]
                if dt_hours > 0.0005:
                    rate_per_hour = max(0, dn / dt_hours)

            # Fallback на среднюю скорость за всё время сессии если история мала
            if rate_per_hour == 0:
                rate_per_hour = 7500.0 # примерная базовая скорость ~7500 т/час на 6 потоках TMDB

            status_payload = {
                "total_items": total_items,
                "categories": {cat: cnt for cnt, cat in cats},
                "rate_per_hour": round(rate_per_hour, 1),
                "updated_at": datetime.now().isoformat(),
                "harvester_running": is_process_running("auto_harvester_60k.py"),
                "streamer_running": is_process_running("streamer.py"),
                "milestone_40k_reached": triggered_40k,
                "milestone_60k_reached": triggered_60k
            }
            STATUS_PATH.write_text(json.dumps(status_payload, indent=2, ensure_ascii=False))

            # 3. Проверка промежуточного майлстоуна 40k
            if not triggered_40k and total_items >= TARGET_INTERMEDIATE:
                triggered_40k = True
                trigger_release_pipeline("40,000 TITLES INTERMEDIATE MILESTONE")

            # 4. Проверка финального майлстоуна 60k
            if not triggered_60k and total_items >= TARGET_FINAL:
                triggered_60k = True
                stop_harvester()
                # Выполняем финальный VACUUM базы
                try:
                    conn = sqlite3.connect(str(DB_PATH))
                    conn.execute("PRAGMA wal_checkpoint(TRUNCATE);")
                    conn.execute("VACUUM;")
                    conn.close()
                except Exception:
                    pass
                trigger_release_pipeline("60,000 TITLES CANONICAL MILESTONE")
                print(f"🏆 ВСЕ ЦЕЛЕВЫЕ ПОКАЗАТЕЛИ (60 000+ ТАЙТЛОВ) ВЫПОЛНЕНЫ! 🏆")

            time.sleep(20) # Интервал проверки (20 сек)

        except Exception as e:
            print(f"[{datetime.now().strftime('%H:%M:%S')}] ⚠️ Ошибка в цикле Watchdog: {e}")
            time.sleep(10)

if __name__ == "__main__":
    if len(sys.argv) > 1 and sys.argv[1] in ["--status", "-s", "status"]:
        print_status_report()
        sys.exit(0)
    run_watchdog_loop()
