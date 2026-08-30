# ADR 0001: canonical source and external runtime data

## Decision

The current Movia Android source, media-parser backend source and Termux MCP
source are assembled under /storage/emulated/0/Movia/Movia_project with one Git
history. GitHub KatiSim/Movia main is the remote history for future changes.

## Exclusions

Large catalog DB/WAL/SHM, torrent/video caches, runtime logs, old APKs,
decompiled/reference captures and secrets remain outside the canonical tree.
Recovery uses manifests/checksums and GitHub Release assets.

## Consequences

A fresh checkout restores source and scripts immediately but needs private
configuration and either a verified catalog snapshot or a rebuild. A baseline
tag means reproducible source state; it does not mean stable playback or
production readiness.
