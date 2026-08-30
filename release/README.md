# Release artifacts

APK and catalog snapshots are GitHub Release assets, not ordinary Git files.

The current checkpoint has no verified installed Movia APK and therefore does not publish an APK asset. `scripts/create-baseline.sh` can prepare ignored staging artifacts only when an explicit verified APK or requested DB snapshot is supplied.

Required release assets for a verified APK baseline:

- `Movia-<version>-code<code>.apk`;
- `SHA256SUMS.txt`;
- `CURRENT_BASELINE.json`;
- optional compressed catalog snapshot and restore script.

Do not name a release Stable unless playback acceptance has actually passed.
