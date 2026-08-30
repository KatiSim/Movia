import os
import re

PARSER_DIR = "/data/data/com.termux/files/home/projects/media-parser"
VIORA_DIR = "/data/data/com.termux/files/home/projects/viora"

print("=== [1/2] СПИСОК ФАЙЛОВ В projects/media-parser ===")
for f in os.listdir(PARSER_DIR):
    print(f"  - {f}")

print("\n=== [2/2] ПОИСК КЛЮЧЕЙ TMDB В ФАЙЛАХ ПРОЕКТА ===")
found_keys = set()

# Поиск в media-parser
for root, _, files in os.walk(PARSER_DIR):
    for file in files:
        if file.endswith((".py", ".env", ".json", ".txt", ".sh", ".md")):
            path = os.path.join(root, file)
            try:
                with open(path, "r", encoding="utf-8", errors="ignore") as f:
                    content = f.read()
                    # Ищем 32-значные hex-строки (формат TMDB v3 ключа)
                    matches = re.findall(r'(?:api_key|tmdb|key|token)[\s=:\'"]+([a-f0-9]{32})', content, re.IGNORECASE)
                    for m in matches:
                        if m != "b997cbe6072fa6ec0c5418b628db9454": # исключаем нерабочий тестовый
                            found_keys.add((file, m))
            except Exception:
                pass

if found_keys:
    print("✅ Найдены потенциальные ключи TMDB в проекте:")
    for fn, k in found_keys:
        print(f"  Файл: {fn} | Ключ: {k[:8]}...{k[-4:]}")
else:
    print("ℹ️ Сохраненных ключей TMDB в существующих файлах не обнаружено.")
