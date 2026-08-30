# Catalog database status

Status at checkpoint: **NOT_FOUND**.

No verified runtime `catalog.db` was found in the checked Termux/shared-storage roots. The repository stores Room schema history through `database/schema`, which points to `android/app/schemas`.

Mutable catalog data must be stored as a separately checksummed release/backup artifact, not in normal Git history. A future snapshot must record:

- exact file path at capture;
- byte size;
- SHA256;
- schema version;
- row count;
- creation timestamp;
- restore command;
- source commit/tag.
