# Provider-union voice gain + fresh cohort-Q

No LazyMedia code was copied in this block.

Fresh cohort-Q:
- 1000 new movies, seed 3427
- exact overlap B-P = 0
- excluded prior/conservative IDs: 16003

Baseline:
- complete: 15
- >=3 voices: 20
- >=3 qualities: 91
- any stream: 437
- near-complete: 37

Completed through existing verified providers:
- Рождественская История: 2x3 -> 3x5
- Ночь в музее 2: 2x3 -> 4x6
- Скорость: 2x3 -> 3x6
- Фантастические твари: Преступления Грин-де-Вальда: 2x4 -> 3x7
- Птичий короб: 2x3 -> 3x6

The last two prove that ProviderContract union can add real voice coverage:
torrent had Original + Dub, while HDRezka supplied a distinct HDrezka Studio leaf.

Final:
- complete: 20
- >=3 voices: 25
- >=3 qualities: 91
- near-complete: 32
- delta complete: +5 / +33.3%

Rutor multi-audio detail study remains fail-closed:
multiple translation labels are not turned into selectable voices until a stable audio_track_index mapping is proven.

Production:
- parser RUNNING
- enricher RUNNING
- health 200
