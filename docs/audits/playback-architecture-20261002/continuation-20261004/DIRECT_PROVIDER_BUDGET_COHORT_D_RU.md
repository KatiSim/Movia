# Background direct-provider budget + fresh cohort-D — 10-minute block

## Когда считаю проект законченным

Не по календарному сроку, а когда одновременно выполняются четыре gate:
1. background enrichment непрерывно пополняет каталог и не зависает на мёртвых providers;
2. title/year identity не допускает подмены фильма;
3. новые независимые 1000-film cohorts показывают устойчивый рост реального coverage;
4. playback использует только реальные voice/quality combinations.

## Performance fix

content_filler раньше делал два безлимитных future.result() перед torrent fallback.

Теперь provider-registry + balancer делят один общий background budget:
- CURRENT до фикса: Walter Mitty ≈34.5 s;
- TARGET: dead direct providers не задерживают torrent enrichment;
- ACTUAL: ≈10.2 s;
- DELTA: −24.3 s / −70.3%.

Focused tests: 14/14 PASS.
Full backend: 294/294 PASS.

## Fresh cohort-D

- 1000 movies, seed 3413;
- overlap B = 0;
- overlap C = 0;
- conservative A exclusions retained.

Baseline:
- complete: 18/1000;
- voices >=3: 21;
- qualities >=3: 84;
- any stream: 441;
- near-complete: 24.

Targeted:
- Восстание планеты обезьян: 2×3 → 3×5;
- Головокружение: 2×5 → 3×5.

Final:
- complete: 20/1000;
- voices >=3: 23;
- qualities >=3: 84;
- near-complete: 22.

Next P0: run torrent concurrently with the 4-second direct-provider budget using a dedicated bounded executor.
