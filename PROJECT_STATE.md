# Movia project state

This file is an evidence summary for the current phone baseline. It deliberately
does not turn unavailable checks into PASS.

## Current version

- versionName: 0.9.23
- versionCode: 293
- package: app.movia.android
- device: 24069PC21G
- installed package: verified by Jarvis 2.0; APK path is present in release/

## Android

Status: source synchronized from
/data/data/com.termux/files/home/projects/movia into android/. The source
contains the current Compose UI, catalog/search, Media3 playback, tests, Room
schema and native agent runtime. The current APK is
release/Movia-0.9.23-code293.apk. A fresh Gradle build was not claimed during
this synchronization.

## Backend

Status: source synchronized from
/data/data/com.termux/files/home/projects/media-parser into backend/. DB files,
runtime caches, logs and .env were excluded. The observed runit service
movia-media-parser is running streamer.py. The parser HTTP endpoint on
127.0.0.1:5001 was not reachable during verification.

## Catalog

- row count: 50491 rows in movies
- schema version: 2, from catalog_meta.schema_version
- current SSOT: /data/data/com.termux/files/home/projects/media-parser/catalog.db
- current SSOT sidecars: catalog.db-wal and catalog.db-shm in the same directory
- canonical repository copy: schema, migration notes, recovery scripts and
  checksums/manifest only; the live DB is not duplicated here

## Playback

Media3 player source, stream resolution and P2P helpers are present. Playback
was not declared fully verified in this baseline. Known evidence remains a risk
of media-identity mismatch after episode selection and stream timeouts even
when stream candidates exist. See docs/FINAL_RELEASE_0_9_23_OR_NEXT.md and the
playback decision records.

## Search

Android and backend search/discovery source is present, including Russian
normalization/search code and backend search service. End-to-end live search
was not claimed because the parser endpoint was unavailable during this check.

## Agent

- native Android agent source: present
- native agent schema version: 2
- Termux MCP source: present under agent/mcp/
- native Movia MCP tools registered by current source: 29
- MCP process: observed running on local port 8940
- MCP HTTP GET /health: returned 404; this is not the MCP POST protocol check
- full MCP handshake/tool inventory: not claimed as PASS in this baseline

## Services

Definitions are stored under agent/services/:

- movia-media-parser
- movia-stream-enricher
- movia-stream-enricher-log
- movia-cache-pruner

The first service was observed running. The other service definitions are
captured for restore; their current health was not converted to PASS. The MCP
process is managed separately by agent/mcp/start.sh.

## Known issues

- Parser health endpoint 127.0.0.1:5001 was unavailable at verification time.
- Playback media identity mismatch remains an open risk.
- Playback can time out even when stream candidates exist.
- Full end-to-end playback and MCP protocol acceptance are not complete.
- Live catalog DB is large and WAL-backed; it is external to Git history.
- Existing runtime state and secrets require private Termux configuration.

## Last verified baseline

- source: /data/data/com.termux/files/home/projects/movia
- backend: /data/data/com.termux/files/home/projects/media-parser
- agent/MCP: /data/data/com.termux/files/home/termux-mcp
- reference: /data/data/com.termux/files/home/projects/zona-reference-20260829-223618
- canonical root: /storage/emulated/0/Movia/Movia_project
- date: 2026-08-30
- Git branch at synchronization: work/current-sync
- status: current checkpoint; not stable/final/production-ready
