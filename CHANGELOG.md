# Changelog

## Current checkpoint — 2026-08-30

- Canonical project root created from the existing `KatiSim/Movia` repository.
- Android Gradle source moved under `android/` to separate it from backend, agent, database and operational documentation.
- Recovered phone JADX output is referenced without duplicating it into Git.
- Added explicit project state, restore contract, verification/backup scripts and secret handling documentation.
- Added database schema pointer and acceptance-test pointer without copying mutable runtime data.
- Added a pre-sync remote branch: `legacy-before-current-sync`.
- Backend, installed APK, catalog.db and Movia Agent remain explicitly unresolved where they were not found on the checked phone.

## Legacy

The repository history before this checkpoint contains the earlier Movia Android source and player/catalog decisions. Historical claims are not promoted to current runtime PASS.

## Rule for future changes

One logical change → relevant tests → `git diff --check` → secret scan → commit → push. Significant verified checkpoints receive a `baseline/current/checkpoint` tag and, when an APK is actually verified, a GitHub Release.
