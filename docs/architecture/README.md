# Architecture

The canonical repository separates Android source, runtime integrations, mutable catalog state and operational recovery instructions.

The Android application is under `android/`. Backend and agent directories deliberately contain status/contract files until their real current source is located. This prevents a clean-looking tree from hiding missing runtime components.

The only accepted sources of current state are:

1. current canonical files;
2. Git history;
3. CURRENT_BASELINE.json;
4. GitHub Release assets with checksums.
