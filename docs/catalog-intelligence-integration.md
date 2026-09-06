# Catalog Intelligence integration contract

Branch: `agent/catalog-intelligence`

This branch owns metadata, recommendations and release/availability state. It does not edit `streamer.py`, Android playback, Details UI, `main` or `integration`.

## Construction

```python
from catalog_intelligence_api import CatalogIntelligenceApi

catalog_intelligence = CatalogIntelligenceApi.from_db_path("/path/to/catalog.db")
```

Schema creation is additive. Existing `movies` rows are not rebuilt or deleted.

## Read contract

- `media_card(media_id)` — full card, trusted-source provenance, relational cast/crew, normalized playable quality/voice snapshot.
- `people_search(query, limit)` — people metadata search.
- `person_projects(person_id=..., name=..., limit=...)` — actor/person to projects metadata.
- `recommendations_for(media_id, limit)` — genre + cast recommendations with score and reason.
- `new_releases(limit)` — only releases that have had a first playable source.
- `release_status(media_id)` — release and availability status.

## Write/sync contract

- `record_metadata_observation(...)` records one trusted-source snapshot. Source trust is configured through `MetadataEngine.register_source()`.
- `observe_playable_source(...)` must be called only after the existing playback/source layer has decided that the source is playable. A `CAMRip` observation is intentionally eligible to promote a release immediately.
- `run_daily_release_tracker(...)` accepts discovery candidates plus already-validated source observations and is idempotent per UTC day unless `force=True`.
- `rebuild_recommendation_index()` creates the initial genre index. Genre metadata observations refresh the affected media index automatically.

## Existing-server wiring needed

The integration agent may expose equivalent routes in the existing backend server. Suggested mapping:

- `GET /catalog/intelligence/media/<id>`
- `GET /catalog/intelligence/media/<id>/recommend`
- `GET /catalog/intelligence/people?q=<name>`
- `GET /catalog/intelligence/person/<id>/projects`
- `POST /catalog/intelligence/metadata/observe`
- `POST /catalog/intelligence/source/observe`
- `GET /catalog/intelligence/releases`
- `POST /catalog/intelligence/releases/daily`

Do not move source resolution into this module. The playback/provider layer should publish a stable `source_key`, normalized or raw `quality`, `voice`, and explicit `playable` boolean to `observe_playable_source()`.

## Daily scheduling

No daemon or `:8888` restart is introduced by this branch. Wire `run_daily_release_tracker()` into the existing daily catalog worker/cron. Source quality/voice changes should additionally call `observe_playable_source()` when discovered so they update immediately rather than waiting for the next daily run.
