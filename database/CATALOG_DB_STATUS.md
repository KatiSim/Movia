# Current catalog database status — 2026-09-09 recovery capture

Runtime SSOT:

`/data/data/com.termux/files/home/projects/media-parser/catalog.db`

Observed while the accepted 0.9.32 phone runtime was healthy:

- size: approximately 757 MiB
- SHA-256 at observation: `5c688c34c899f8d3cc3319db8714a34a2d9a5e1325298c1e8b298d2e943a97a9`
- `PRAGMA quick_check`: `ok`
- `movies` rows: `71,899`
- `catalog_meta.schema_version`: `4`
- `catalog_meta.catalog_revision`: `9718`
- `catalog_meta.normalization_version`: `1`

The database is continuously mutated by catalog/background workers, so its hash/revision changes after this observation. It is deliberately excluded from ordinary Git history.

Create a private SQLite-consistent recovery snapshot with:

```bash
bash scripts/export-runtime-catalog.sh
```

Restore one with:

```bash
bash scripts/import-runtime-catalog.sh SNAPSHOT.sqlite.gz
```

A source-only clean recovery can rebuild a functional catalog using the tracked schema/sync/enrichment code and privately configured provider/TMDB credentials, but it cannot reproduce a later live DB byte-for-byte without a saved snapshot.
