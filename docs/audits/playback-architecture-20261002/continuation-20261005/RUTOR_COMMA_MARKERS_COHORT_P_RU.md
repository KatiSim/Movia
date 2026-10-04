# Rutor comma voice markers + fresh cohort-P

No LazyMedia code was copied.

Provider evidence:
- Zona source refs: unavailable / PROVIDER_ERROR.
- Persisted real refs for Kinoplay / VideoCDN / Alloha: 0.
- Octopus exact sample (Joker 2019) reached an iframe, but it was a static external landing with no m3u8/mpd/player markers.
- No new provider was activated without playable non-zero evidence.

Rutor evidence:
- Live release: Beetlejuice (1988) | P, P2, A.
- Detail page explicitly lists professional multi-voice translations first, then professional two-voice, plus original audio.
- Parser now accepts comma-separated P/P2/A markers.
- One torrent is NOT exploded into fake selectable voices because audio_track_index is unknown.
- P,P2,A -> Professional (MVO)
- D,P,P2,A -> Dub

Tests:
- focused 3/3 PASS
- full backend 322/322 PASS

Fresh cohort-P:
- 1000 new movies, seed 3426
- overlap B-O = 0
- baseline complete 12
- >=3 voices 15
- >=3 qualities 76
- any stream 434
- near-complete 25

All 25 near-complete cards were checked.
None currently resolves to a truthful >=3 voices x >=3 qualities selectable inventory.

Final:
- complete 12
- >=3 voices 15
- >=3 qualities 76
- near-complete 25
- delta complete: 0

This zero delta is intentional: no voice option was fabricated.
