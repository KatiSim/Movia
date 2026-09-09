# Movia Android 0.9.32

Package `app.movia.android`, version `0.9.32 (302)`.

## Stack

- Jetpack Compose Material 3
- Media3/ExoPlayer 1.9.3 (HLS/DASH/session)
- Room 2.8.4
- DataStore 1.2.1
- WorkManager 2.11.0
- compile/target SDK 35, min SDK 26
- Java/Kotlin bytecode target 17

Default control plane is `http://127.0.0.1:8888`; override at build time with `MOVIA_CONTROL_PLANE_URL`.

## Build

From repository root:

```bash
bash scripts/bootstrap-debug-keystore.sh
bash scripts/build.sh
```

Exact accepted APK is already preserved at `release/Movia-0.9.32-code302.apk`.

## Design and behavior

Use `docs/DESIGN_SYSTEM_0.9.32.md` and `docs/INTERACTION_LOGIC_0.9.32.md`; do not use old 0.3.x documentation as current UI authority.
