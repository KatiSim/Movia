import subprocess
import os

print("=== [1/2] ТОЧНЫЙ ЛОГ ОШИБОК KOTLIN ===")
res = subprocess.run(
    ["./gradlew", ":app:compileDebugKotlin", "--no-daemon"],
    capture_output=True,
    text=True
)

output = res.stdout + "\n" + res.stderr
for line in output.splitlines():
    if line.startswith("e: ") or "error:" in line.lower() or "unresolved" in line.lower():
        print(line)

print("\n=== [2/2] ЭТАЛОННАЯ СТРУКТУРА ИЗ КАНОНИЧЕСКОЙ СБОРКИ 182 ===")
canon_path = "/data/data/com.termux/files/home/MoviaApp/Movia/Каноническая версия/0.3.75-code182-20260825/Полный проект-0.3.75-code182-20260825/app/src/main/java/app/movia/android/data/catalog/CatalogRepository.kt"

if os.path.exists(canon_path):
    with open(canon_path, "r", encoding="utf-8") as f:
        lines = f.readlines()
        print("Первые 45 строк эталонного файла:")
        print("".join(lines[:45]))
        print("\nПоследние 25 строк эталонного файла:")
        print("".join(lines[-25:]))
else:
    print(f"Канонический файл не найден по пути: {canon_path}")
