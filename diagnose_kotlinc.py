import subprocess
import os
import re

print("=== [1/2] ЗАПУСК КОМПИЛЯЦИИ С ВЫВОДОМ СТЕКА ОШИБОК ===")
res = subprocess.run(
    ["./gradlew", ":app:compileDebugKotlin", "--stacktrace", "--no-daemon"],
    capture_output=True,
    text=True
)

output = res.stdout + "\n" + res.stderr

# Ищем критические строки ошибок
print("=== [2/2] АНАЛИЗ СБОЯ ===")
errors = []
for line in output.splitlines():
    if any(k in line for k in ["Caused by:", "e: ", "Exception", "Error", "OutOfMemory", "Method too large"]):
        errors.append(line)

if errors:
    for e in errors[:25]:
        print(e)
else:
    print(output[-2000:])

with open("/data/data/com.termux/files/home/kotlinc_error.log", "w", encoding="utf-8") as f:
    f.write(output)

print("\nПолный лог сохранен в: ~/kotlinc_error.log")
