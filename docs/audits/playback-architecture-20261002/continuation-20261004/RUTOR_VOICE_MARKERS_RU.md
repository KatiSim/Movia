# Verified Rutor voice markers — 10-minute block

- Verified from live Rutor detail metadata:
  - P = Профессиональный (МВО)
  - P2 = Двухголосый (ДВО)
  - A = Авторский (Одноголосый)
- Existing Movia contract `Лицензия → Дубляж` is now used only as a fallback after explicit studio/type/original markers.
- 10-title Rutor sample: unknown voices **214 → 84**, known coverage **58.4% → 83.7%**.
- Identity regression: “Гладиатор (2000)” does not accept “Амазонки и гладиаторы (2001)”.
- Live:
  - Матрица: Jaskier + ДВО + Дубляж + МВО.
  - Марсианин: Авторский + Дубляж.
- Full backend: 292/292 PASS.
- Active focused: 3/3 PASS.
- Parser and enricher RUNNING; health HTTP 200.
