# LazyMedia Deluxe 3.466 — массовый structural playback benchmark

Дата: 4 октября 2026.

## Метод

Проверен pinned LazyMedia Deluxe 3.466 provider engine, SHA-256 `637705386744f29445223fded2ad2df6aeb72295cfc9ce327609e7f0c7a72a00`. APK приложения отдельно не устанавливался: те же Dex/provider classes запускались headless через Android `app_process` в изолированном Context. Legacy Activity, player, WorkManager и background services не запускались.

Для каждого фильма движок делал тот же путь: registry → provider search → exact title/year match → article parser → `k30` tree → раскрытие lazy `h30` branches → все concrete `j30` leaves. Поэтому тест программно эквивалентен раскрытию всех доступных папок озвучек/качеств, но без UI-задержек.

В telemetry не сохранялись URL, headers, cookies, tokens или signed locators.

## Random-200

Запрошено: **200** случайных movie cards из `catalog.db`, seed **20261004**.

Валидных engine-runs: **190/200**. Ещё **10** cases остались harness-level `app_process` failures и не считаются ни успехом, ни `NO_SOURCE`.

Из 190 валидных:
- хотя бы один concrete candidate: **71 / 190 = 37.4%**;
- среднее число leaves среди найденных: **2.39**; median: **2**; max: **5**;
- median first candidate: **1904 ms**; p90: **5174 ms**;
- ≥2 providers: **21**;
- ≥1 explicit voice: **0**; ≥2 voice: **0**; ≥3 voice: **0**;
- ≥1 normalized quality: **22**;
- ≥2 qualities: **10**;
- ≥3 qualities: **0**.

Provider combinations:
- no resolved provider: **119**;
- Zona only: **31**;
- Filmix only: **19**;
- Filmix + Zona: **21**.

Concrete leaves: **170**, из них video **103**, torrent **67**.

## Coverage по возрасту контента
- 1920-1989: 23/46 = 50.0% с candidate.
- 1990-1999: 11/16 = 68.8% с candidate.
- 2000-2009: 12/21 = 57.1% с candidate.
- 2010-2019: 13/29 = 44.8% с candidate.
- 2020-2026: 12/78 = 15.4% с candidate.

Сильный эффект: свежий/obscure long-tail покрыт заметно хуже старого/популярного каталога. Это очень похоже на проблему, которую мы видим в Movia.

## Реальное состояние providers в 2026

В random benchmark concrete leaves фактически дали только:
- **Zona.mobi** — resolved у 52 валидных titles;
- **Filmix** — resolved у 40 валидных titles.

Многие остальные active providers 3.466 сейчас регулярно дают `TIMEOUT`, `IOException`, `NO_EXACT_MATCH` или `NO_PLAYABLE_EPISODE`. Особенно важно: HDRezka часто находит exact article, но текущий transport не даёт concrete playable leaf в этом harness.

Это означает: переносить надо архитектуру LazyMedia, но нельзя считать его старые endpoint contracts автоматически рабочими в 2026.

## Озвучки

Главный неожиданный результат: **0/190** валидных random movie runs дали явное имя voice. Это не из-за того, что lazy folders не раскрывались — probe принудительно раскрыл все `h30` branches. Concrete movie leaves, которые реально дошли до конца, были в основном Zona MP4 и Filmix torrent/file variants без структурированного voice label.

18-second deep-control на популярных фильмах это подтвердил: увеличение budget с 6.5 s не восстановило voice metadata на валидных runs.

Следовательно, требование Movia `>=3 озвучки` нельзя выполнить простым копированием текущего runtime output LazyMedia 3.466. Нужны живые provider contracts / обновлённые adapters, сохранив LazyMedia VariantTree architecture.

## Качества

Нормализованные explicit qualities были беднее ожидаемого:
- 22/190 имели хотя бы одно качество;
- 10/190 — минимум два;
- 0/190 — три.

Типичный успешный Filmix movie давал `720p + 1080p + unspecified release`; Zona чаще давал `MP4 • LQ/HQ`, которые текущий normalizer не превращает в фиктивные 480/720/1080. Это правильно: качество нельзя выдумывать из `LQ/HQ` без фактической track/media проверки.

## Collision / franchise test

Проверено 9 cards одного/похожего названия разных лет:
- Человек-паук (2002): candidates=1, providers=zona.mobi, qualities=none, voices=none.
- Новый Человек-паук (2012): candidates=4, providers=filmix,zona.mobi, qualities=1080p,720p, voices=none.
- Новый Человек-паук: Высокое напряжение (2014): candidates=5, providers=filmix,zona.mobi, qualities=1080p,720p, voices=none.
- Человек-паук: Возвращение домой (2017): candidates=6, providers=filmix, qualities=1080p,720p, voices=none.
- Человек-паук: Нет пути домой (2021): candidates=2, providers=filmix, qualities=1080p, voices=none.
- Дюна (2021): candidates=2, providers=zona.mobi, qualities=none, voices=none.
- Дюна (1984): candidates=0, providers=none, qualities=none, voices=none.
- Бэтмен (1989): candidates=0, providers=none, qualities=none, voices=none.
- Бэтмен (2022): candidates=2, providers=zona.mobi, qualities=none, voices=none.

Ни одного наблюдаемого cross-year match в этих 9 случаях нет. Это подтверждает ценность LazyMedia-подхода `search → exact item → article`, а в нашем wrapper дополнительно работает строгий title/year guard.

Важно: этот headless benchmark не рендерил UI, поэтому он **не проверяет визуальное совпадение poster/description пикселей**. Он проверяет именно provider/article/stream identity. Для Movia poster/synopsis остаются canonical catalog metadata и не должны меняться stream providers.

## Как устроена сильная часть LazyMedia

1. `Services`/`gs0` — registry providers и capabilities.
2. `vq0` — общий provider descriptor: ListArticles, Article parser, settings/query templates.
3. Search возвращает provider article references, а не сразу плоский URL.
4. Article parser сначала подтверждает metadata/identity.
5. Playback content строится как `k30` tree.
6. `h30` — deferred/lazy branch loader; тяжёлые сезоны/эпизоды раскрываются только при необходимости.
7. `j30` — concrete media leaf с URL, quality, headers/subtitles/options.
8. Series имеют естественную hierarchy `voice → season → episode → files`.
9. Provider failures изолированы друг от друга; discovery параллельный и incremental.
10. Tree traversal bounded: depth/node/result/deadline limits.

Именно эту архитектуру стоит переносить в Movia как собственный backend ProviderRegistry/VariantTree. UI/Activities/старый player и stale endpoint secrets переносить не нужно.

## Итог

Тест опровергает опасное предположение «достаточно скопировать текущие результаты LazyMedia и сразу получим 3×3 на каждом фильме». **Нет**: в текущем 2026 network environment LazyMedia 3.466 сам массово не даёт 3 voices/3 qualities.

Но тест подтверждает другое: его архитектура существенно лучше нашей старой flat-stream схемы — exact provider article, hierarchical variants, lazy expansion, isolated provider failure и bounded discovery. Её и надо использовать как каркас, а providers обновлять/переносить по одному на живые current contracts.
