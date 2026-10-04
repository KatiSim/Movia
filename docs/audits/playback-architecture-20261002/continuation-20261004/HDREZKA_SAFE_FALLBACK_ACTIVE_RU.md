# HDRezka safe fallback activation — 10-minute block

- Full backend: 290/290 PASS.
- Focused HDRezka/Lazy compatibility: 14/14 PASS.
- Active parser updated with safe fallback improvements.
- Streamer restarted: RUNNING, PID 19348.
- /health: HTTP 200, ~6 ms.
- Live Interstellar resolve: 10 streams, voice=Дубляж, qualities=240p/360p/480p/720p, ~1.7 s.
- GET transport hdrzk.org + Host aliases works and returns guest session cookies.
- Translator AJAX via transport remains HTTP 403.
- Direct public HTTPS AJAX returns HTTP 200 but only anti-bot HTML, not API JSON.
- No anti-bot bypass attempted.
- Production therefore keeps fast embedded fallback; full 10–22 voice expansion remains gated.
