# Movia 0.9.32 canonical recovery APK

This directory intentionally contains the exact APK installed on the phone at the accepted 0.9.32 baseline.

- file: `Movia-0.9.32-code302.apk`
- package: `app.movia.android`
- versionName: `0.9.32`
- versionCode: `302`
- size: `24,321,643` bytes
- SHA-256: `25e9c2a3a49e4649376b871f469bec3df39160c7ef86743d317a41855f23f49b`
- source commit used to build it: `35d1d9f82396eab7641359633740a642586eb254`

Verify with:

```bash
sha256sum -c release/SHA256SUMS.txt
```

Ordinary `scripts/build.sh` builds to the Gradle output directory and deliberately does not overwrite this recovery artifact.
