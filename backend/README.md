# Movia local media backend — 0.9.32 baseline

The phone-local control plane runs `streamer.py` on loopback port 8888.

## Responsibilities

- catalog/details/search/person APIs from SQLite catalog;
- TMDB metadata/person enrichment;
- direct HTTP/HLS/DASH discovery and validation;
- exact series season/episode stream routing;
- provider reliability/ranking;
- bounded local P2P fallback;
- adjacent-episode prewarm;
- cache pruning and background enrichment policy.

## Start

Preferred production-on-phone path is runit via `agent/services/`:

```bash
bash scripts/setup-local-runtime.sh
sv up movia-torrserver movia-media-parser
```

Manual development start:

```bash
cd backend
python3 -m pip install -r requirements.txt
MOVIA_TORRSERVER_URL=http://127.0.0.1:18090 python3 -u streamer.py
```

Health:

```bash
curl http://127.0.0.1:8888/health
```

## P2P boundary

Direct URLs are preferred. Local torrent playback uses loopback TorrServer MatriX.144.1 with aria2 metadata/fallback infrastructure. Cloud mode is direct-only and does not proxy torrent/video media through a VPS.

## Runtime catalog

`catalog.db` is mutable runtime state and excluded from Git. Schema/recovery code is versioned. Use `scripts/export-runtime-catalog.sh` for a consistent private snapshot and `scripts/import-runtime-catalog.sh` to restore one.

## Tests

The recovery baseline completed 198 backend tests successfully. Run all regular backend unit tests (excluding the network diagnostic `test_tmdb_connection.py`) with the command documented in the recovery blueprint.
