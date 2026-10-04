# Movia — активация Zona type 26 / Kinoplay, 4 октября 2026

## Результат

Портированный ранее extractor **26 `kinoplay`** активирован в локальном production parser `/data/data/com.termux/files/home/projects/media-parser`.

Изменены только:
- `zona_legacy_adapters.py`;
- `zona_contract.py`.

Перед заменой сделана резервная копия:
`/data/data/com.termux/files/home/.cache/movia-architecture-20261002/active-parser-backup-20261004-kinoplay`.

## Проверки

- `py_compile`: **PASS**.
- Kinoplay focused contract tests: **7/7 PASS**.
- Active legacy non-network suite: **248 тестов; 5 failures + 7 errors**.
- Это **точно тот же** baseline ошибок, что был подтверждён 3 октября; новых failures/errors: **0**.
- Реальные сетевые тесты `test_zona_query.py` и `test_tmdb_connection.py` в этот suite не включались.

## Runtime

Старый supervised `streamer.py` PID **20440** завершён. Runit поднял PID **23603**.

Сразу после появления нового PID `streamer.py --check` один раз вернул `STOPPED`: процесс ещё не принимал health-запрос. Затем:
- `/health` → HTTP **200**;
- `streamer.py --check` → **RUNNING**;
- живой `streamer.py`: **ровно 1** процесс.

Это зафиксировано как startup readiness race, а не как успешная проверка с первой попытки.

## Контроль playback API после активации

| mediaId | HTTP | rows | voices | qualities | bad track index |
|---|---:|---:|---:|---:|---:|
| 45127 | 200 | 0 | 0 | 0 | 0 |
| 164 | 200 | 75 | 28 | 8 | 0 |
| 375 | 200 | 30 | 13 | 7 | 0 |
| 39012 | 200 | 1 | 1 | 1 | 0 |

Количество источников, озвучек и качеств совпадает с контрольным состоянием 3 октября.

## Сохранность

Android UI не открывался, APK не устанавливался, системные настройки не менялись, Android source tree этим блоком не записывался.

У пользовательского Android worktree:
- HEAD остаётся `c5d1d3c882e07238c6f2a8fd308e8cec32f85936`;
- SHA-256 index остаётся `0a6e8e0d5143d26ed257c1686d849ab9b4d92eb244fb4f1b8cbd843c0771ac7a`;
- текущий hash `git status --porcelain` — `065a8153e9a074e097e7d0465c68a6da153d33bef67e7a9c4ee648790729e48f`, 114 строк.

Текущий status уже **не совпадает** с историческим checkpoint 3 октября. Этот блок worktree не изменял, поэтому ничего в нём не откатывалось и не «восстанавливалось».

## Что не доказано

- Реальный Kinoplay/type 26 stream пока не декодирован: предыдущий live probe не получил raw type-26 source ref.
- Новых фильмов в этом блоке не тестировалось.
- 500 полных native voice × quality матриц не завершены.
- APK 327 не установлен и не проверен как native build.
- Внешний hosting/HTTPS/PostgreSQL не развёрнут.
