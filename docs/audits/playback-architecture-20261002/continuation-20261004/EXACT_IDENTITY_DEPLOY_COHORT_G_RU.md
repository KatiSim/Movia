# Exact-identity APK deployment + fresh cohort-G

## Android deployment
- versionCode: 327
- assembleDebug: PASS
- built APK SHA-256: 3e512d81a86912e50a057b1552715102d8d49f998c6bf8d0b4fdb9e3a1852a3e
- installed base.apk SHA-256: same
- installed with pm install -r -d; app data preserved
- cold launch: no Movia FATAL EXCEPTION / ANR found

## Collision smoke
- Человек-паук (2002), mediaId=226:
  - playback mediaId=226
  - Media3 mediaItemId=226
  - no rebind to another Spider-Man
- Spider-Man (1977), mediaId=621453:
  - playback mediaId=621453
  - Media3 mediaItemId=621453
  - no rebind to 2002 or another title

The 2002 concrete source itself returned a bad HTTP status; that is a source-quality issue, not an identity mix-up.

## Fresh cohort-G
- 1000 new movies, seed 3416
- exact overlap B/C/D/E/F = 0
- baseline complete: 13/1000
- voices >=3: 14
- qualities >=3: 96
- any stream: 462
- near-complete: 33

Targeted:
- Петля времени: 2×3 → 3×4

Final:
- complete: 14/1000
- voices >=3: 15
- qualities >=3: 96
- near-complete: 32
