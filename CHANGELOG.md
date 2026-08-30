# Changelog

## Current baseline — 0.9.23 / code 293

This checkpoint synchronizes the current phone/Termux Movia system into one
canonical project root and the existing GitHub repository.

- Android package app.movia.android, versionName 0.9.23, versionCode 293.
- Current Android source includes Compose UI, catalog/search, Media3 playback,
  Room schema and the native agent runtime.
- Catalog architecture uses the media-parser backend and catalog schema v2.
- Search/discovery and playback resolver/P2P source are present in backend.
- Agent-native Android API and Termux MCP integration are included; current
  source registers 29 native Movia MCP tools.
- Current service definitions and acceptance/regression tests are included.
- Large catalog DB, runtime caches, logs, old APKs and secrets are intentionally
  external or ignored.

Known limitations at this checkpoint:

- The parser endpoint was not reachable during baseline inspection.
- Playback still has documented media-identity and stream-timeout risks.
- Full end-to-end playback verification has not been claimed.

## Legacy

The repository contains earlier pre-current architecture history, including the
old 0.3.x line. A remote branch named
legacy-before-current-sync preserves the previous GitHub main state, and
legacy-destination-before-current-sync preserves the prior phone canonical
working tree before replacement.

Future changes follow:

change -> relevant tests -> git diff --check -> secret scan -> commit -> push
