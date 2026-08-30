# Recovery architecture

Recovery has three layers:

1. Git repository: source, schema, scripts, tests, docs and manifests.
2. GitHub Release: current APK, SHA256SUMS.txt, baseline manifest and an
   optional compressed catalog snapshot.
3. Private Termux state: credentials, live catalog DB/WAL/SHM, runtime caches
   and service state.

RESTORE.md is the only required starting document for a new agent. It points to
the current paths, explains the deliberate exclusions and gives the commands
for rebuild, restore, service startup and verification.

Never delete private source, DB, cache or backup directories until the pushed
commit and the GitHub Release assets have been independently verified.
