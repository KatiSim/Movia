# Movia project state

## Canonical baseline

The approved baseline is Movia **0.9.32 / code 302**, package `app.movia.android`.
The canonical Git reference is `v0.9.32` and the active Android source root is
`/data/data/com.termux/files/home/projects/movia`.

## Verified installed build

The canonical APK was assembled successfully and installed over the existing application without
clearing user data. The built APK and installed `base.apk` matched by SHA-256:

`8c608bf16f5798b388200e9b4334c249ae8e751765ee66063559632021d9d4c0`

## Current approved behavior

- Home hero artwork uses the same framing principle as Details and no longer over-zooms backdrops.
- Home title/metadata are below artwork; remaining time is contained in the primary Continue button.
- The redundant Home progress line is removed.
- Home and Details use a restrained, lower, more transparent bottom artwork fade.
- Details metadata/title/year/duration presentation uses the shared canonical formatting helpers.
- Bottom navigation uses the approved solid icons and has no rectangular press artifact.
- Player ±10-second feedback has no arc/ripple semicircle; only the seek icon remains.
- Seek-feedback icons move symmetrically away from the center using orientation-aware screen-width offsets.

## Repository policy

The repository tip is the source of truth. Historical `.bak` files, old APK binaries, temporary
screenshots, generated diagnostics, acceptance output files and runtime secrets are not part of the
canonical tree. Git history remains available for forensic recovery, but old working-tree snapshots
must not be restored over this baseline without an explicit decision.
