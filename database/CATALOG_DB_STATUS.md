# Current catalog database status

This status was captured from the correct Jarvis 2.0 context on 2026-08-30
immediately before the current baseline manifest was regenerated.

## Current SSOT

Path:

    /data/data/com.termux/files/home/projects/media-parser/catalog.db

Sidecars:

    /data/data/com.termux/files/home/projects/media-parser/catalog.db-wal
    /data/data/com.termux/files/home/projects/media-parser/catalog.db-shm

Observed facts at capture time:

- catalog.db size: 793509888 bytes
- catalog.db SHA256: 750d5f7f85cf41711f4347176e48d2cc557021ff42665a6528bf3f98a44c3a72
- catalog.db-wal size: 202922392 bytes
- catalog.db-wal SHA256: 9f5287704c1f749eddc3d409ab1fac5b4fdd50e56cb601467142d1ea24aff505
- catalog.db-shm size: 425984 bytes
- catalog.db-shm SHA256: 6c57294b65a908e8e82f918a3932b0d5fbea2878a21e9335d39891573dd6af8e
- catalog_meta.schema_version: 2
- catalog_meta.catalog_revision: 279
- movies rows: 50473

The database is live and changes as backend workers run. CURRENT_BASELINE.json is
the machine-readable manifest generated from the same system; regenerate it
before a later checkpoint. The canonical project stores this status and the
recovery code, not the live DB or sidecars.

A release snapshot must be made with scripts/create-baseline.sh using
MOVIA_DB_SNAPSHOT=1, then its checksum must be listed in SHA256SUMS.txt.
