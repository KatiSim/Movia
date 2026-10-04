# Native torrent persistence + fresh cohort-K

No LazyMedia code was copied.

Background content_filler now rewrites validated magnet rows through the Movia ProviderContract before save_content():
- direct rows remain unchanged;
- provider / info_hash / voice / quality / seeders remain real;
- logical_source_id + provider_item_id are added before persistence;
- TV card-level packs are not relabeled as exact episodes.

Live DB proof:
- Harry Potter DH Part I: stored native magnet IDs 0/29 -> 26/29 in one ordinary enrichment pass.
  Three old rows were not rediscovered and therefore were deliberately left untouched.
- The Nice Guys: 2 voices x 3 qualities -> 3 x 3; 18 rediscovered magnet rows were persisted natively.

Tests:
- focused persistence: 10/10 PASS
- full backend: 315/315 PASS

Enricher service config now exports MOVIA_ENABLE_TORRENT_PROVIDER_CONTRACT=1.

Fresh cohort-K:
- 1000 new movies, seed 3421
- exact overlap B-J = 0
- baseline complete: 14
- >=3 voices: 18
- >=3 qualities: 100
- any stream: 466
- near-complete: 24

Final:
- complete: 15
- >=3 voices: 19
- >=3 qualities: 100
- near-complete: 23
