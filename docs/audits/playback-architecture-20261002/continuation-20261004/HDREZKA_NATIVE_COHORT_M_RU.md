# Movia-native HDRezka activation + fresh cohort-M

No LazyMedia source code was copied.

Verified behavior studied:
- search -> exact article;
- translator branches;
- movie/series leaf structure;
- real quality alternatives from provider payload.

Rewritten for Movia:
- ProviderContract / VariantTree;
- Movia logger: hdrezka_provider_adapter;
- exact title/year search from the real result link DOM;
- exact catalog identity on flattened leaves.

Live:
- Interstellar: 1 exact article, 10 leaves, 240p/360p/480p/720p, 10/10 native IDs.
- Matrix: 1 exact article, 10 leaves, 240p/360p/480p/720p, 10/10 native IDs.
- Provider registry status: OK / provider=hdrezka for both.
- Parser and enricher flags enabled.

Old flat active files removed after dependency check:
- rezka_provider.py
- rezka_parser.py

Tests:
- focused 22/22 PASS
- full backend 319/319 PASS

Fresh cohort-M:
- 1000 new movies, seed 3423
- exact overlap B-L = 0

Baseline:
- complete 13
- >=3 voices 15
- >=3 qualities 89
- any stream 425
- near-complete 32

Completed:
- Основатель: 2x3 -> 4x4
- Тупой и ещё тупее: 2x3 -> 3x4

Final:
- complete 15
- >=3 voices 17
- >=3 qualities 89
- near-complete 30
