# Remove old Movia conflicts + fresh cohort-F

Removed from active runtime:
- stream_extractor.py
- zona_importer.py
- zona_cache_migrator.py
- zona_cache_migrator.state.json

They had no production imports/service references. stream_extractor was especially unsafe because its old defaults could fabricate Dub/1080p metadata.

Kept:
- LegacyProviderEngine / LegacyPlaybackResolver: this is the current LazyMedia 3.466 compatibility engine and VariantTree path.
- zona_legacy_adapters / zona_playback_architecture: still used by working Zona/HDRezka/Kinoplay branches.

Android source cleanup:
- Before: known mediaId could fall back from exact identity to title search.
- After: known mediaId never falls back to title; only blank identity-less callers may use title fallback.
- compileDebugKotlin: BUILD SUCCESSFUL.
- Targeted unit-test runner did not finish within the invocation limit; no assertion failure observed.
- Installed APK not updated in this block.

Backend:
- full suite 294/294 PASS.
- active parser py_compile PASS.
- parser/enricher RUNNING, health 200.

Fresh cohort-F:
- 1000 new movies, seed 3415.
- exact overlap B/C/D/E = 0.
- baseline complete 13/1000.
- Мгла: 2×3 -> 3×4.
- final complete 14/1000.
