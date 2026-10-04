# Movia-native Zona VariantTree rewrite + cohort-J

## Rewrite rule
No LazyMedia code was copied. Verified behavior was studied, then reimplemented for Movia:
- exact identity;
- search/article semantics;
- season -> episode hierarchy;
- concrete source leaves;
- reloadable logical source behavior.

Movia implementation:
- zona_provider_adapter.py
- logger: zona_provider_adapter
- provider id: movia:zona
- ProviderContract / VariantTree
- exact catalog media_id
- preserves headers, source type, tracks, subtitles and reload metadata
- never maps HQ/LQ to invented 1080p/720p

The adapter is installed in active runtime but MOVIA_ENABLE_ZONA_PROVIDER_CONTRACT remains OFF.
Live Interstellar exact identity passed, but protected source resolution returned ZONA_PROVIDER_ERROR / 0 leaves.

Tests:
- focused: 21/21 PASS
- full backend: 310/310 PASS

## Fresh cohort-J
- 1000 new movies, seed 3419
- exact overlap B-I = 0
- baseline complete: 9
- >=3 voices: 10
- >=3 qualities: 83
- any stream: 454
- near-complete: 29

Completed:
- Миссия невыполнима: Протокол Фантом: 2x3 -> 3x3
- Тройной форсаж: Токийский Дрифт: 2x4 -> 3x4
- Тренировочный день: 2x3 -> 3x3

Final:
- complete: 12
- >=3 voices: 13
- >=3 qualities: 83
- near-complete: 26
