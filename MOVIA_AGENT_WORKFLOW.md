# Movia agent workflow

The canonical working tree is `/data/data/com.termux/files/home/projects/movia`.

For every application change:

1. Inspect the existing implementation and preserve unrelated work.
2. Modify the authoritative source component rather than a one-off copy.
3. Run `git diff --check` on touched files.
4. Build with `./gradlew :app:assembleDebug --no-daemon`.
5. Install with replacement (`pm install -r -d`) without clearing app data.
6. Compare SHA-256 of the built APK and installed `base.apk`.
7. Launch and verify the affected screen/behavior.
8. Keep generated APKs, logs, screenshots, diagnostics and secrets out of Git.
9. Commit only after verification; tag explicitly approved canonical baselines.

The current approved baseline is `v0.9.32` / code `302`.

## Critical Runtime Invariants
1. **Single PlaybackSession / MediaSession**:
   - Always use `MoviaPlaybackRegistry.obtain(context.applicationContext)`.
   - Never create a second `PlaybackSession` or `MediaSession` directly.
   - Verify session ownership and ensure only the designated owner calls `release()`.
   - Prevent `IllegalStateException: Session ID must be unique`.
2. **Build & Deploy Hygiene**:
   - Never run concurrent Gradle builds or simultaneous APK installations.
   - Successful compilation is NEVER proof of functionality.
   - Full cycle required: build → install → cold launch → verify crash log.
