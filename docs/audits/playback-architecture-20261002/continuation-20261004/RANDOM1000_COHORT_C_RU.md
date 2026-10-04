# Fresh random-1000 cohort C — 10-minute block

- Cohort-C: 1000 **movies only**, seed 3412.
- Exact overlap with cohort-B: **0**.
- Cohort-A IDs were never persisted and the catalog grew 96,980 → 97,098 rows, so exact A overlap cannot be proven retroactively.
- To minimize repeat risk, 2,001 unique IDs were conservatively excluded using four deterministic A reconstructions from snapshot/current catalogs.

Baseline:
- complete 3 voices × 3 distinct qualities: **10/1000**
- voices >=3: **15/1000**
- qualities >=3: **84/1000**
- any stream: **453/1000**
- near-complete: **33**

Targeted through normal content_filler:
- Невероятная жизнь Уолтера Митти: 2×4 → 3×4
- Шерлок Холмс: Игра теней: 2×3 → 3×3
- Взвод: 2×3 → 3×4
- Окча: 2×3 → 3×3

Final:
- complete: **14/1000**
- voices >=3: **19/1000**
- qualities >=3: **84/1000**
- near-complete: **29**

Delta: **+4 complete / +40%** from cohort-C baseline.

Operational finding: one targeted enrichment took ~34.5 s because background _process_row waited on a protected Zona/balancer error path before torrent completion. Next performance target is to cap that background wait so working torrent enrichment is not delayed.
