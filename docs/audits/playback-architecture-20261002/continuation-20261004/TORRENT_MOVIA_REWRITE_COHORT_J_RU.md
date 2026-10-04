# Movia-native torrent VariantTree + fresh cohort-J

## Rewrite policy
No LazyMedia code was copied.

Observed behavior only:
- torrent search/list keeps multiple choices;
- series article groups season -> episode -> torrent-list leaves;
- each torrent remains a distinct leaf.

Reimplemented with Movia's own:
- ProviderContract / VariantTree;
- torrent_resolver data;
- exact catalog identity;
- torrent_provider_adapter logger;
- stable btih-based logical identity.

## Production result
ProviderContract now preserves:
- seeders
- info_hash
- release_title

Tracker changes do not change logical_source_id.

Exact series rule:
generic season/card rows are never relabeled as a requested episode.

The actual local playback service boundary is now CatalogStreamService._rows.

Live HTTP:
- Matrix (43): 34/34 magnet rows have logical_source_id + provider_item_id.
- Interstellar (158): 93/93.
- Providers preserved: Rutor / Apibay / YTS.

Tests:
- focused: 18/18 PASS
- full backend: 313/313 PASS

## Cohort-J
Fresh 1000 movies, seed 3419, overlap B-I = 0.

Baseline:
- complete: 9
- >=3 voices: 10
- >=3 qualities: 83
- any stream: 454
- near-complete: 29

Final:
- complete: 12
- >=3 voices: 13
- >=3 qualities: 83
- near-complete: 26

The +3 happened through the running background enricher between sampling and the explicit targeted pass; the three selected titles were already complete when the manual pass started.
