# Changelog

## 0.9.32 canonical recovery baseline — 2026-09-09

This checkpoint is the accepted phone build and the GitHub recovery source of truth.

- exact installed APK is preserved in `release/` with SHA-256 verification;
- unified dark/gold visual system documented, including logo, glow, outlines and BottomBar;
- BottomBar is one glass surface with no per-tab rectangles or bounded rectangular ripple;
- Media3 system notification carries poster artwork and returns to Movia when tapped;
- catalog scroll position survives Details return; route identity is mediaId-based;
- actors/directors/creators have photos and person filmography screens;
- unknown metadata is fail-closed instead of fabricated defaults;
- season selectors use drag-handle close and isolated horizontal season paging;
- quality/voice switching uses actual stream evidence and one buffering spinner;
- exact series routing, progress boundaries, adjacent prewarm and bounded P2P fallback were hardened;
- TorrServer MatriX.144.1 is documented/pinned as localhost P2P streaming primary with aria2 fallback infrastructure;
- backend full regression: 198 tests OK;
- Android full gate: BUILD SUCCESSFUL, 58 tasks.

Recovery documentation:

- `RESTORE.md`
- `docs/DESIGN_SYSTEM_0.9.32.md`
- `docs/INTERACTION_LOGIC_0.9.32.md`
- `docs/RECOVERY_BLUEPRINT_0.9.32.md`

## Historical baselines

Earlier 0.9.23 and 0.3.x states remain available through Git history/tags for archaeology only. They are not current recovery authority. The repository history is intentionally preserved rather than rewritten.
