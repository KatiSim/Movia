# Remove recursive flat balancer + fresh cohort-H

Removed from old Movia balancer layer:
- batch_update_balancer_streams()
- CLI __main__ direct writer path
- recursive torrent fallback is now disabled by default in both query_zona_api() and query_open_balancer_stream()

Why:
- torrent discovery already runs as a separate branch;
- the old recursive fallback could duplicate P2P rows inside a flat balancer result;
- batch_update_balancer_streams directly overwrote movies.streams and bypassed modern identity/persistence checks.

Tests:
- focused: 9/9 PASS
- full backend: 295/295 PASS
- active py_compile: PASS

Fresh cohort-H:
- 1000 new movies, seed 3417
- exact overlap B-G = 0
- baseline complete: 10/1000
- >=3 voices: 13
- >=3 qualities: 79
- any stream: 467
- near-complete: 32

Completed:
- Человек-паук: Вдали от дома: 2x3 -> 3x4
- Крёстный отец 2: 2x3 -> 3x3
- Варкрафт: 2x3 -> 4x3
- Армагеддец: 2x3 -> 3x3
- Джуманджи: 2x3 -> 3x3

Final:
- complete: 15/1000
- >=3 voices: 18
- >=3 qualities: 79
- near-complete: 27
