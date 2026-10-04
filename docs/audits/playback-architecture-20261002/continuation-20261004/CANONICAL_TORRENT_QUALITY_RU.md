# Canonical torrent quality + honest coverage — 10-minute block

- Confirmed: content_filler preserves the full torrent inventory; it does not reduce Rutor/YTS/Apibay to one stream.
- Found real bug: quality aliases inflated the 3-quality target (1080p vs FullHD 1080, 720p vs HD 720).
- YTS now emits canonical 1080p / 720p / 4K labels.
- variant_coverage now uses Movia canonical_quality() and normalized voice keys.
- Random-1000 with strict 3 voices × 3 distinct qualities: 7 complete cards.
- Focused tests: 17/17 PASS.
- Full backend: 292/292 PASS.
- Active parser + enricher restarted and RUNNING.
- Interstellar torrent-only: 55 rows, 2 real voices, 4 real qualities; full stored inventory: 7 voices + 4 qualities => complete.
- Avengers full stored inventory: 2 voices + 3 qualities => partial, so enrichment continues.
