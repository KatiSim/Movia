# Changelog

## 0.9.32 canonical — 2026-09-17

This checkpoint replaces the previous working-tree baselines as the canonical Movia source state.

- Canonicalized the current Compose UI and Media3 player implementation.
- Unified title/year/rating/country/type/duration presentation across media surfaces.
- Reworked Home hero framing to match Details and removed excessive backdrop zoom.
- Moved Home identity/metadata below the artwork and playback remaining time into the CTA.
- Removed the redundant Home progress bar.
- Standardized the softer/lower artwork fade on Home and Details.
- Finalized solid bottom-navigation icons and removed press/ripple rectangle artifacts.
- Removed the player seek-feedback semicircle/ripple and kept the ±10-second icon only.
- Added orientation-aware adaptive seek-feedback spacing for portrait and landscape.
- Removed tracked historical `.bak` source snapshots and old local APK/diagnostic artifacts from the canonical tree.
- Expanded ignore rules so generated/runtime artifacts cannot accidentally re-enter the repository.

Verification for the installed canonical APK: `HASH_MATCH_OK` with SHA-256
`8c608bf16f5798b388200e9b4334c249ae8e751765ee66063559632021d9d4c0`.
