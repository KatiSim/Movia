# Zona ProviderContract ownership + fresh cohort-L

No LazyMedia source code was copied.

Verified behavior only:
- movie -> source leaf set;
- series -> season -> episode -> source leaves;
- HQ/LQ are not concrete quality evidence.

Movia already had a native zona_provider_adapter.py. This block did not duplicate it; it hardened routing around it.

Live Zona check:
- Interstellar: old PROVIDER_ERROR/0, new 0
- Matrix: old PROVIDER_ERROR/0, new 0
- Spider-Man: No Way Home: old PROVIDER_ERROR/0, new 0

Therefore MOVIA_ENABLE_ZONA_PROVIDER_CONTRACT remains OFF.

Added ownership switch:
- flag OFF: legacy balancer Zona remains available
- flag ON: ProviderContract owns Zona and the balancer branch is skipped
- prevents double Zona requests during future activation

A live targeted pass exposed a regression: balancer_integration used os.environ without importing os.
Fixed and covered by regression test.

Tests:
- focused: 21/21 PASS
- full backend: 318/318 PASS

Fresh cohort-L:
- 1000 new movies, seed 3422
- overlap B-K: 0

Baseline:
- complete: 12
- >=3 voices: 14
- >=3 qualities: 104
- any stream: 456
- near-complete: 39

Completed:
- Крепкий орешек 3: Возмездие: 2x3 -> 4x4

Final:
- complete: 13
- >=3 voices: 15
- >=3 qualities: 104
- near-complete: 38

Production:
- parser RUNNING
- enricher RUNNING
- health 200
- Zona ProviderContract flag remains OFF
