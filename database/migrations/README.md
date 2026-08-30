# Catalog migrations and recovery

The current catalog reports catalog_meta.schema_version = 2. The database also
contains the catalog revision and normalization version in catalog_meta.

The backend source of schema and recovery behavior is:

- backend/catalog_schema_v2.py
- backend/database.py
- backend/catalog_sync.py
- backend/restore_all.py
- backend/restore_working_state.py

The live DB is WAL-backed. Stop writers before replacing it, keep the database,
WAL and SHM set together when making an operational backup, and verify a
release SHA256SUMS.txt before restore.
