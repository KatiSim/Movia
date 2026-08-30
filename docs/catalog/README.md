# Catalog architecture

The current catalog is the media-parser SQLite catalog. Its current metadata is
catalog schema version 2, normalization version 1 and catalog revision 277 at
the last generated baseline manifest.

The current SSOT is private:

    /data/data/com.termux/files/home/projects/media-parser/catalog.db

The repository stores:

- backend discovery/sync/search code;
- Android Room schema snapshots;
- database schema and migration notes;
- read-only row-count/checksum manifest;
- restore instructions.

The live DB is WAL-backed and must not be copied into the canonical project or
ordinary Git history. A consistent compressed snapshot can be created only when
requested through scripts/create-baseline.sh with MOVIA_DB_SNAPSHOT=1 and then
attached to a GitHub Release.
