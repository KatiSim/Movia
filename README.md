# Movia

Movia is the canonical Android client for the current media catalog and playback stack.

## Canonical version

- package: `app.movia.android`
- versionName: `0.9.32`
- versionCode: `302`
- canonical ref: `v0.9.32`
- Android source root: `/data/data/com.termux/files/home/projects/movia`
- status: **canonical / approved phone baseline**

The current canonical tree contains the Compose UI, catalog/search logic, Media3 playback,
stream selection, native agent runtime, acceptance tests and recovery documentation. Historical
`.bak` source snapshots, old APKs, diagnostic dumps, generated acceptance outputs and runtime
secrets are intentionally excluded from the repository tip.

## Build

```bash
./gradlew :app:assembleDebug --no-daemon
```

The APK is generated at:

```text
app/build/outputs/apk/debug/app-debug.apk
```

## Install without clearing user data

```bash
APK=app/build/outputs/apk/debug/app-debug.apk
cat "$APK" | rish -c 'cat > /data/local/tmp/movia-debug.apk'
rish -c 'pm install -r -d /data/local/tmp/movia-debug.apk'
rish -c 'rm -f /data/local/tmp/movia-debug.apk'
```

Always verify that the SHA-256 of the installed `base.apk` matches the built APK.

## Canonical UI/playback state

The 0.9.32 baseline includes the approved Home/Details artwork treatment, unified metadata
presentation, current bottom navigation, player gesture behavior and the adaptive ±10-second
seek-feedback placement used in portrait and landscape.

## Restore

See `RESTORE.md`. Current baseline evidence is recorded in `CURRENT_BASELINE.json` and
`PROJECT_STATE.md`.
