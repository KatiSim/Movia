# Restore Movia canonical baseline

Canonical reference: `canonical-0.0.1`

## Restore source

```sh
git fetch origin --tags
git switch main
git pull --ff-only origin main
```

The `main` branch and `canonical-0.0.1` tag point to the same canonical snapshot.

## Exact canonical APK

Path: `canonical/Movia-0.0.1-canonical.apk`

SHA-256:

```text
4b3adcf4789b83a77c70b9d69f4f13b2d4a29b50ee37084fafdb308b2f73e89f
```

Install:

```sh
pm install -r -d canonical/Movia-0.0.1-canonical.apk
```

## Build from source

```sh
./gradlew :app:assembleDebug --no-daemon --max-workers=1 -Pkotlin.compiler.execution.strategy=in-process
```

Then verify the resulting APK against the canonical behavior and run cold-launch/crash/visual QA.

Local secrets and machine-specific files are intentionally not stored in GitHub. Use `.env.example` / local configuration where applicable.
