# Movia-native Collaps VariantTree rewrite + cohort-I

## Rewrite rule followed
No LazyMedia source code was copied. Only verified behavior was studied:
- article/embed discovery;
- HLS/DASH leaves;
- season -> episode hierarchy;
- audio-track ordering.

Reimplemented for Movia with:
- exact catalog media_id identity;
- exact imdb_id from that card;
- ProviderContract / VariantTree;
- provider_item_id + logical_source_id;
- exact S/E scoping;
- Movia logger: collaps_provider_adapter;
- no guessed 480p/720p qualities.

The adapter is installed in active runtime but gated by MOVIA_ENABLE_COLLAPS_PROVIDER_CONTRACT.
The flag is currently OFF because live Collaps returned 0 rows through both the old and new path, so a non-zero parity proof was not possible.

Tests:
- focused adapter/discovery: 10/10 PASS
- adapter + ProviderContract + old Collaps fixtures: 17/17 PASS
- full backend: 300/300 PASS

## Fresh cohort-I
- 1000 new movies, seed 3418
- exact overlap B-H = 0
- baseline complete: 15
- >=3 voices: 23
- >=3 qualities: 103
- any stream: 476
- near-complete: 45

Completed:
- Типа крутые легавые: 2x3 -> 3x4

Final:
- complete: 16
- >=3 voices: 24
- >=3 qualities: 103
- near-complete: 44
