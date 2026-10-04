# HDRezka exact series aliases + fresh cohort-O

No LazyMedia code was copied.

Next-provider probe:
- Kinoplay: no persisted real source refs
- VideoCDN: no persisted real source refs
- Alloha: no persisted real source refs
No fixture/source key was treated as real.

HDRezka series hardening:
- exact catalog title + original_title aliases are supported
- provider display names such as “Очень странные дела / Загадочные события” are split only on '/' and matched by exact normalized alias segment
- no fuzzy contains matching
- exact S/E remains mandatory

Live:
- Breaking Bad S01E01: 10 exact leaves
- Sherlock S01E01: 10 exact leaves
- Stranger Things / Очень странные дела S01E01: 10 exact leaves
- final production registry: OK, provider=hdrezka, episode=(1,1), logical IDs 10/10

Tests:
- focused: 19/19 PASS
- full backend: 322/322 PASS

Fresh cohort-O:
- 1000 new movies, seed 3425
- overlap B-N = 0
- baseline complete 17
- >=3 voices 21
- >=3 qualities 96
- any stream 460
- near-complete 35

Completed:
- 007: Спектр: 2x3 -> 3x7

Final:
- complete 18
- >=3 voices 22
- >=3 qualities 96
- near-complete 34
