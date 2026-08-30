# ADR 0001: Canonical source layout

Date: 2026-08-30

The canonical Movia checkout is `/data/data/com.termux/files/home/Movia` on the phone and the `main` branch of `KatiSim/Movia` remotely.

Android source is kept under `android/`. The recovered JADX tree is referenced by a symlink because it is evidence/recovery material, not a buildable source replacement. Runtime backend, agent, catalog database, APK, and service definitions are not represented as present unless an inventory verifies them.

No cache, runtime log, token, credential, private signing key, media file, or large database is part of ordinary Git history.
