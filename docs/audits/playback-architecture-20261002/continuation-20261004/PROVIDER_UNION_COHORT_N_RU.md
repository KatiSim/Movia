# ProviderContract union + fresh cohort-N

No LazyMedia code was copied.

Provider candidates checked:
- Kinoplay
- VideoCDN
- Alloha

No safe non-zero source-ref was available for a new activation in this block, so no fixture/source key was treated as real.

HDRezka live study on 7 popular films:
- exact article: 7/7
- 10 leaves per title
- real qualities: 240p / 360p / 480p / 720p
- current honest voice set: Dub only

This confirms HDRezka helps quality coverage but does not solve the dominant voice deficit.

Provider registry architecture fixed:
- BEFORE: first successful ProviderContract provider returned immediately.
- AFTER: all enabled successful ProviderContract providers are unioned.
- HDRezka can no longer starve future Collaps/Zona/Filmix voice variants.
- Octopus diagnostic status no longer discards already collected playable leaves.

Tests:
- focused: 17/17 PASS
- full backend: 320/320 PASS

Live after activation:
- Interstellar 2014: status OK, provider hdrezka, 10 rows
- logical_source_id: 10/10
- provider_item_id: 10/10

Fresh cohort-N:
- 1000 new movies, seed 3424
- exact overlap B-M: 0

Baseline:
- complete: 10
- >=3 voices: 11
- >=3 qualities: 86
- any stream: 452
- near-complete: 30

Completed:
- Divergent: 2x3 -> 3x7

Final:
- complete: 11
- >=3 voices: 12
- >=3 qualities: 86
- near-complete: 29
