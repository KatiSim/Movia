# Movia canonical snapshot — 2026-09-24

This snapshot is the rollback point for the current installed application.

## Identity

- package: `app.movia.android`
- versionName: `0.0.1`
- versionCode: `307`
- canonical tag: `canonical-0.0.1`
- canonical APK SHA-256: `4b3adcf4789b83a77c70b9d69f4f13b2d4a29b50ee37084fafdb308b2f73e89f`

## Verification completed before canonicalization

- current `app-debug.apk` SHA-256: `4b3adcf4789b83a77c70b9d69f4f13b2d4a29b50ee37084fafdb308b2f73e89f`
- installed Android `base.apk` SHA-256: `4b3adcf4789b83a77c70b9d69f4f13b2d4a29b50ee37084fafdb308b2f73e89f`
- hashes are identical
- `git diff --check`: PASS
- most recent task report: assembleDebug PASS, install PASS, cold launch PASS, crash check PASS, Home cold-start QA PASS, Library unfinished-progress Resume Hero QA PASS

## Included

Current application code and resources, UI, navigation, catalog/home/library/player/profile/settings logic, database schema files, tests, benchmark module, IBM Plex Sans resources/license, ADRs/audits, agent workflow and rollback documentation.

## Intentionally excluded

Machine-local/generated or sensitive material: `.env*`, `local.properties`, Gradle/build output directories, transient `*.log` files, local DBs/diagnostics, and local historical `.movia-checkpoints/`. These are not required to reconstruct the canonical source and must not be published as secrets or historical versions.
