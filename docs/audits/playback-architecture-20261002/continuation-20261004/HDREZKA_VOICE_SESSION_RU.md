# HDRezka voice expansion — 10-minute block

- Исправлен structural bug: embedded cdnplayer больше не скрывает translator branches.
- На живых страницах обнаружено 10 переводов для «Интерстеллар» и 22 для «Матрица».
- Scoped Set-Cookie → Cookie добавлен только внутри resolve; cookies не сохраняются.
- Live после этого: «Интерстеллар» дал voice «Дубляж» и 240p/360p/480p/720p.
- Полный translator expansion пока блокируется provider transport: текущий config host hdrzk.org отвечает 403 на base/article.
- Восстановлены exact benign headers LazyMedia 3.466: User-Agent, Cookie, Referer, X-Hdrezka-Android-App, X-Hdrezka-Android-App-Version.
- Guest token cookie: dle_user_token.
- Focused tests: 9/9 PASS.
- Full backend: 290/290 PASS.
- Active parser не изменён: live contract ещё не готов к безопасной активации.
