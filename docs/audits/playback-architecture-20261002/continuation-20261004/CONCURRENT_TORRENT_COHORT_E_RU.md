# Concurrent torrent enrichment + fresh cohort-E

## Performance
Walter Mitty:
- original: ~34.5 s
- after 4 s direct-provider budget: ~10.2 s
- after concurrent torrent: ~4.5 s
- total reduction: ~86.9%

Torrent now starts before the direct-provider wait in a dedicated 6-worker executor.

Tests:
- focused 14/14 PASS
- full backend 294/294 PASS

## Cohort-E
- 1000 new movies, seed 3414
- exact overlap B/C/D = 0
- conservative A exclusions retained

Baseline:
- complete: 6
- >=3 voices: 9
- >=3 qualities: 80
- any stream: 454
- near-complete: 33

Completed:
- Годзилла: 2×3 → 3×4
- Крид: Наследие Рокки: 2×3 → 3×5
- Когда Гарри встретил Салли: 2×4 → 3×4
- Аэроплан: 2×3 → 3×4

Final:
- complete: 10
- >=3 voices: 13
- >=3 qualities: 80
- near-complete: 29
