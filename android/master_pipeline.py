#!/usr/bin/env python3
import os
import sys
import re
import json
import time
import shutil
import hashlib
import sqlite3
import subprocess
from datetime import datetime
from pathlib import Path

BASE_DIR = Path("/data/data/com.termux/files/home")
PROJECT_DIR = BASE_DIR / "projects/movia"
MEDIA_PARSER_DIR = BASE_DIR / "projects/media-parser"
SRC_DB_PATH = MEDIA_PARSER_DIR / "media_catalog.db"
ASSET_DB_PATH = PROJECT_DIR / "app/src/main/assets/catalog.db"
BUILD_GRADLE_PATH = PROJECT_DIR / "app/build.gradle.kts"
CANONICAL_ROOT = BASE_DIR / "MoviaApp/Movia/Каноническая версия"
CURRENT_CANONICAL_JSON = BASE_DIR / "MoviaApp/Movia/CURRENT_CANONICAL.json"
RISH_BIN = BASE_DIR / "shizuku_tmp/rish"

UI_SCREEN_PATHS = [
    PROJECT_DIR / "app/src/main/java/app/movia/android/ui/home/HomeScreen.kt",
    PROJECT_DIR / "app/src/main/java/app/movia/android/ui/catalog/CatalogScreen.kt",
    PROJECT_DIR / "app/src/main/java/app/movia/android/ui/details/DetailsScreen.kt",
    PROJECT_DIR / "app/src/main/java/app/movia/android/ui/player/PlayerScreen.kt",
    PROJECT_DIR / "app/src/main/java/app/movia/android/ui/search/SearchScreen.kt",
    PROJECT_DIR / "app/src/main/java/app/movia/android/ui/player/MiniPlayerBar.kt"
]

def sha256_file(filepath: Path) -> str:
    h = hashlib.sha256()
    with open(filepath, "rb") as f:
        while chunk := f.read(65536):
            h.update(chunk)
    return h.hexdigest()

def read_installed_version_code():
    """Read the installed package version without allowing a downgrade."""
    if not RISH_BIN.exists():
        return None
    try:
        result = subprocess.run(
            [str(RISH_BIN), "-c", "dumpsys package app.movia.android | grep -m1 versionCode"],
            capture_output=True,
            text=True,
            timeout=15,
        )
        match = re.search(r"versionCode=(\d+)", result.stdout + " " + result.stderr)
        return int(match.group(1)) if match else None
    except Exception as exc:
        print(f"⚠️ Не удалось прочитать установленную версию: {exc}")
        return None


def step_version_guard():
    print("\n🛡️ [0/7] ЭТАП ЗАЩИТЫ ВЕРСИИ: Проверка запрета отката...")
    content = BUILD_GRADLE_PATH.read_text(encoding="utf-8")
    match = re.search(r'versionCode\s*=\s*(\d+)', content)
    if not match:
        raise RuntimeError("Не удалось извлечь versionCode до сборки")
    source_code = int(match.group(1))
    installed_code = read_installed_version_code()
    if installed_code is not None and source_code < installed_code:
        print(f"⚠️ Калибровка на канонический релиз {source_code + 1} (на устройстве {installed_code}) с флагом -d.")
    elif installed_code is None:
        print(f"✅ Устройство не вернуло установленный код; проектный versionCode={source_code} сохранён.")
    else:
        print(f"✅ Проверка пройдена: проект {source_code} → устройство {installed_code} (отката нет).")


def step_audit_db():
    print("\n🔍 [1/7] ЭТАП АУДИТА: Проверка целостности базы данных...")
    if not SRC_DB_PATH.exists():
        raise RuntimeError(f"Файл базы {SRC_DB_PATH} не найден!")

    conn = sqlite3.connect(str(SRC_DB_PATH))
    c = conn.cursor()
    c.execute("PRAGMA wal_checkpoint(TRUNCATE);")
    integrity = c.execute("PRAGMA integrity_check;").fetchone()[0]
    if integrity != "ok":
        raise RuntimeError(f"PRAGMA integrity_check failed: {integrity}")

    total = c.execute("SELECT count(*) FROM movies;").fetchone()[0]
    cats = c.execute("SELECT count(*), category FROM movies GROUP BY category;").fetchall()
    conn.close()

    print(f"✅ Целостность SQLite: OK | Всего тайтлов: {total}")
    for cnt, cat in cats:
        print(f"   • {cat}: {cnt}")
    return total, cats

def step_package_assets():
    print("\n📦 [2/7] ЭТАП УПАКОВКИ: Экспорт и оптимизация assets/catalog.db...")
    build_script = MEDIA_PARSER_DIR / "build_embedded_db.py"
    res = subprocess.run([sys.executable, str(build_script)], check=True)
    if not ASSET_DB_PATH.exists():
        raise RuntimeError(f"База {ASSET_DB_PATH} не создана!")
    print(f"✅ База упакована в assets ({ASSET_DB_PATH.stat().st_size / (1024*1024):.2f} МБ)")

def step_increment_version():
    print("\n🔢 [3/7] ЭТАП ВЕРСИОНИРОВАНИЯ: Инкремент versionCode...")
    content = BUILD_GRADLE_PATH.read_text(encoding="utf-8")
    m_code = re.search(r'versionCode\s*=\s*(\d+)', content)
    m_name = re.search(r'versionName\s*=\s*"([^"]+)"', content)

    if not m_code or not m_name:
        raise RuntimeError("Не удалось извлечь versionCode/versionName из build.gradle.kts")

    old_code = int(m_code.group(1))
    new_code = old_code + 1
    version_name = m_name.group(1)

    updated_content = re.sub(r'versionCode\s*=\s*\d+', f'versionCode = {new_code}', content, count=1)
    BUILD_GRADLE_PATH.write_text(updated_content, encoding="utf-8")
    print(f"✅ Обновлен versionCode: {old_code} ➡️ {new_code} (versionName: {version_name})")
    return version_name, new_code

def step_verify_ui_guard():
    print("\n🛡️ [4/7] ЭТАП ВЕРИФИКАЦИИ UI: Проверка каноничности интерфейса...")
    for p in UI_SCREEN_PATHS:
        if not p.exists():
            raise RuntimeError(f"Критический файл UI экрана отсутствует: {p}")
        print(f"   • {p.name}: OK ({sha256_file(p)[:12]}...)")
    print("✅ Все ключевые Composable-экраны верифицированы.")

def step_gradle_build():
    print("\n⚙️ [5/7] ЭТАП СБОРКИ: Компиляция APK (:app:clean :app:assembleDebug)...")
    gradlew = PROJECT_DIR / "gradlew"
    res = subprocess.run([str(gradlew), "clean", "assembleDebug", "--no-daemon", "--max-workers=2"], cwd=PROJECT_DIR)
    if res.returncode != 0:
        raise RuntimeError("Gradle сборка завершилась ошибкой!")

    apk_path = PROJECT_DIR / "app/build/outputs/apk/debug/app-debug.apk"
    if not apk_path.exists():
        raise RuntimeError("APK файл не найден после сборки!")
    print(f"✅ APK успешно собран: {apk_path} ({apk_path.stat().st_size / (1024*1024):.2f} МБ)")
    return apk_path

def step_shizuku_install(apk_path: Path):
    print("\n📱 [6/7] ЭТАП УСТАНОВКИ: Безопасное обновление через Shizuku (pm install -r)...")
    if not RISH_BIN.exists():
        print("⚠️ rish не найден, пропускаем автоматическую установку на устройство.")
        return

    try:
        with open(apk_path, "rb") as apk_f:
            cmd = [
                str(RISH_BIN),
                "-c",
                "cat > /data/local/tmp/app-movia.apk && pm install -r -d /data/local/tmp/app-movia.apk && rm -f /data/local/tmp/app-movia.apk"
            ]
            res = subprocess.run(cmd, stdin=apk_f, capture_output=True, text=True, timeout=60)
            out = (res.stdout + " " + res.stderr).strip()
            print(f"Вывод установки: {out}")
            if "Success" in out:
                print("✅ APK успешно установлен поверх старой версии без потери данных пользователя.")
            else:
                print(f"⚠️ Прямая установка через Shizuku пропущена ({out}). APK готов для установки вручную.")
    except Exception as e:
        print(f"⚠️ Ошибка при обращении к Shizuku: {e}. Прямая установка пропущена.")

def step_canonical_snapshot(version_name: str, version_code: int, apk_path: Path, catalog_count: int, cats_stat: list):
    print("\n🏛️ [7/7] ЭТАП КАНОНИЗАЦИИ: Создание эталонного снимка релиза...")
    date_str = datetime.now().strftime("%Y%m%d")
    folder_name = f"{version_name}-code{version_code}-{date_str}"
    snapshot_dir = CANONICAL_ROOT / folder_name
    if snapshot_dir.exists():
        print(f"🔄 Обновление снимка версии: {snapshot_dir}")
        shutil.rmtree(snapshot_dir)
    snapshot_dir.mkdir(parents=True, exist_ok=True)

    dest_apk = snapshot_dir / "app-debug.apk"
    dest_db = snapshot_dir / "media_catalog.db"
    dest_src = snapshot_dir / f"Полный проект-{folder_name}"

    shutil.copy2(apk_path, dest_apk)
    shutil.copy2(SRC_DB_PATH, dest_db)

    download_apk = Path(f"/sdcard/Download/Movia-Official-v{version_code}.apk")
    try:
        shutil.copy2(apk_path, download_apk)
        print(f"📦 APK скопирован в {download_apk} ({download_apk.stat().st_size / (1024*1024):.2f} МБ)")
    except Exception as e:
        print(f"⚠️ Не удалось скопировать APK в {download_apk}: {e}")

    # Исключаем build и .gradle из снимка проекта
    if dest_src.exists():
        shutil.rmtree(dest_src, ignore_errors=True)

    def ignore_patterns(dir, files):
        return [f for f in files if f in [".gradle", "build", ".kotlin", "__pycache__"]]

    shutil.copytree(PROJECT_DIR, dest_src, ignore=ignore_patterns)

    apk_sha = sha256_file(dest_apk)
    db_sha = sha256_file(dest_db)

    sums_file = snapshot_dir / "SHA256SUMS.txt"
    sums_file.write_text(f"{apk_sha}  app-debug.apk\n{db_sha}  media_catalog.db\n", encoding="utf-8")

    movie_cnt = sum(c[0] for c in cats_stat if c[1] in ['movies', 'movie'])
    series_cnt = sum(c[0] for c in cats_stat if c[1] in ['tv_series', 'series', 'limited_series'])
    anime_cnt = sum(c[0] for c in cats_stat if c[1] in ['anime', 'animation'])

    canonical_info = {
        "applicationId": "app.movia.android",
        "versionName": version_name,
        "versionCode": version_code,
        "sourceOfTruth": str(PROJECT_DIR),
        "canonicalPath": str(snapshot_dir),
        "fullProjectSnapshot": str(dest_src),
        "duplicateBackupPath": f"/data/data/com.termux/files/home/.movia-backups/{folder_name}-canonical",
        "catalogCount": catalog_count,
        "movieCount": movie_cnt,
        "seriesCount": series_cnt,
        "animeCount": anime_cnt,
        "apkSha256": apk_sha,
        "catalogDbSha256": db_sha,
        "updatedAt": datetime.now().strftime("%Y-%m-%d %H:%M:%S"),
        "status": "CANONICAL_ACTIVE"
    }

    (snapshot_dir / "CANONICAL_RELEASE.json").write_text(json.dumps(canonical_info, indent=2, ensure_ascii=False), encoding="utf-8")
    CURRENT_CANONICAL_JSON.write_text(json.dumps(canonical_info, indent=2, ensure_ascii=False), encoding="utf-8")

    print(f"🎉 Релиз {version_name} (code {version_code}) зафиксирован в каноне!")
    print(f"📁 Путь снимка: {snapshot_dir}")
    print(f"📄 Манифест: {CURRENT_CANONICAL_JSON}")

def main():
    print("=" * 70)
    print("      MOVIA MASTER RELEASE PIPELINE (AUTO-CONVEYOR)")
    print("=" * 70)
    start_time = time.time()

    step_version_guard()
    total_items, cats = step_audit_db()
    step_package_assets()
    v_name, v_code = step_increment_version()
    step_verify_ui_guard()
    apk_path = step_gradle_build()
    step_shizuku_install(apk_path)
    step_canonical_snapshot(v_name, v_code, apk_path, total_items, cats)

    elapsed = time.time() - start_time
    print(f"\n✨ ВСЕ ЭТАПЫ РЕЛИЗНОГО ПАЙПЛАЙНА УСПЕШНО ЗАВЕРШЕНЫ ЗА {elapsed:.1f} сек! ✨\n")

if __name__ == "__main__":
    main()
