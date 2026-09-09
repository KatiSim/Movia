# Movia 0.9.32 — interaction and button logic

This document records the behavior of the installed 0.9.32 build. Source baseline: `35d1d9f82396eab7641359633740a642586eb254`.

## 1. Top-level navigation

`Главная`, `Каталог`, `Моё` are top-level destinations. Their content is kept in `SaveableStateProvider` scopes. Switching destination changes the selected index. A catalog reset generation is emitted only for an intentional top-level destination change and is consumed once; returning from a Details screen must not reuse the same reset event.

Catalog uses a hoisted `LazyGridState` plus `CatalogRetentionState` fallback. Before a card opens, the app captures request/filter key, item IDs, pagination state, first visible index and exact pixel offset. Details is routed by `mediaId + title`, not title alone.

## 2. Home

### Hero body

Tap hero body → open Details for that media identity.

### Hero center Play

Tap center Play → start/resume the exact playback title. If stored progress is resumable, playback starts from `resumePositionMs`; completed progress (>=98%) is treated as restart-from-zero.

### Shelf card

Tap card → Details. `mediaId` remains the stable identity through enrichment.

## 3. Details

### Back arrow / system Back / swipe-down

Return to previous Details item or the catalog/home route. Returning to Catalog preserves scroll position.

### Favorite heart

Toggles the exposed My List state. Current implementation synchronizes the legacy favorite/watch-later collections behind the single UI action.

### Watch button

Starts the selected media. Series resume text targets stored season/episode when available; movie resume uses stored timeline. Playback opens full player.

### `Выбор сезона и серий`

Opens season/episode browser. The season browser closes by downward drag from its handle/header; horizontal season swipes never mean close.

### Download

If not downloaded, enqueues `DownloadScheduler` with current Wi-Fi-only preference. If already downloaded, deletes the local download and updates library state.

### Share

Uses Android `ACTION_SEND` with title and source URL when available.

### Actor / director / creator

Tap person → resolve `/api/person?name=...`, show person photo/department and all matching Movia projects from TMDB combined credits intersected with local catalog. Tap a project → push that exact `mediaId + title` onto Details stack.

### Similar / franchise card

Pushes another Details identity without losing the previous item; Back pops the Details stack.

## 4. Season/episode screens

- season chip tap → animate/scroll pager to that season;
- horizontal swipe → next/previous season only within valid range;
- selected season is programmatically kept in the visible `LazyRow` viewport;
- episode row tap → starts exact `SxxEyy` playback;
- progress row status comes from stored episode progress;
- >=98% is `Просмотрено`;
- header swipe-down over threshold closes;
- there is no duplicate left arrow/right X in the player season sheet.

## 5. Playback start and resolver

Starting a new title first persists progress of the previous active title. The request carries `mediaId`, optional season/episode, preferred voice/quality, current local download URI when present, poster artwork and candidate stream options.

Explicit series requests bypass fragile title-only discovery and resolve exact season/episode through the backend route.

Saved quality/voice are **preferences**, not hard filters. Explicit user selection in the current action is strict. This prevents a saved 720p preference from rejecting a healthy direct 1080p/HLS fallback while still honoring a user who explicitly selected 720p.

READY budget remains bounded; provider discovery is not allowed to hold the UI indefinitely.

## 6. Full player actions

### Back

Order of handling:

1. if controls are locked → unlock;
2. if playback settings open → close settings;
3. if episode selector open → close selector;
4. if landscape → return portrait;
5. otherwise persist progress, stop/clear playback, leave player.

### Lock

Locks player chrome. The lock/unlock action remains the controlled way to restore UI interaction.

### Picture-in-Picture

Requests Android PiP using current video/source bounds. Media continues through the Media3 session.

### Settings

Opens quality/voice/control settings and hides normal player controls.

### Center Play/Pause

Toggles Media3 player. If status is BUFFERING, the center contains the only animated loading spinner.

### ±10 seconds

Persistent buttons, when enabled, seek exactly ±10,000 ms. Side repeated taps use the same 10-second seek increments and show directional feedback.

### Previous/next episode

Available only for real series with known episode counts. Previous crosses a season boundary to the last episode of the previous season. Next crosses to episode 1 of the next known non-empty season. No fabricated episode counts are used.

### Timeline

Dragging immediately updates scrub position; release calls `session.seekTo`. Accessibility `setProgress` maps to the same behavior.

### Fullscreen

Toggles portrait/landscape orientation. Back from landscape exits fullscreen before exiting playback.

## 7. Voice and quality switching

The player settings list is built from actual stream variants. Selecting quality:

1. choose the best voice compatible with that quality;
2. find exact stream variant;
3. `switchToStream` at current playback position;
4. persist title-level quality and compatible voice.

Selecting voice finds a variant at current quality and switches at the same timeline position.

During a switch, one central spinner indicates buffering; the status pill remains text-only (`Подключение к потоку…` or resolver status).

Adaptive HLS provider claims without real track evidence are shown as `Auto` until Media3 exposes actual variants. Media3 track resolution replaces stale provider-only labels.

## 8. Subtitles

- `Нет` disables text track type;
- `Авто` clears overrides and enables text type;
- concrete subtitle option installs a Media3 track override.

The selected subtitle ID is stored in playback state and preference state.

## 9. Auto-next and adjacent prewarm

When a real series episode is READY and enters the final ~90 seconds, the app requests `prewarm-next` once per playback generation. The backend performs adjacent P2P metadata prewarm asynchronously so the HTTP request itself returns quickly.

At natural completion, Auto-next may start the next known episode when enabled. Unknown episode structure does not invent a next episode.

## 10. P2P fallback logic

Local P2P is a fallback, not the preferred cloud/direct path.

Current phone path:

1. direct HTTP/HLS/DASH when viable;
2. TorrServer local sidecar for low-latency torrent stream startup;
3. aria2 remains fallback/metadata infrastructure.

TorrServer resolves the exact episode file by path/season/episode; aria2 file indexes are never assumed to equal TorrServer file IDs.

## 11. System media notification

`MoviaPlaybackService` owns the MediaSession. Current media item supplies title and artwork. System Play/Pause acts on the same Player instance. Tapping notification uses a `PendingIntent` targeting Movia `MainActivity`.

## 12. Mini-player

- body tap → reopen full player;
- Play/Pause → toggles active session;
- Close → persist progress, stop and clear playback;
- progress line mirrors current percentage watched.

## 13. Progress and history

Playback progress is periodically persisted every two seconds while valid. Switching content persists the previous active state first. `PlaybackProgress.isResumable` excludes completed >=98% items from Continue Watching while retaining completion markers.

History, favorites/watch-later, downloads and progress are in the private Room database. Playback/app settings are in DataStore. Android uninstall removes these private app-data stores unless separately backed up.

## 14. Catalog identity and return behavior

A media identity is `mediaId`; title is display/search metadata. Opening Details never deliberately resets Catalog. `LazyGridState` is owned above Details route and survives its composition. A reset trigger is one-shot; the same generation cannot scroll the catalog to item 0 twice.

Late catalog enrichment may modify metadata only for the same ID. Title-cache aliases cannot silently overwrite a different ID.

## 15. Person logic

For movies, the creative label is `Режиссёр`; for TV/series it is `Создатели`. Person lookup accepts Russian or original names. Backend resolves TMDB person details + combined movie/TV credits, then intersects against Movia local catalog so project cards are actually openable in Movia.

## 16. Error handling

Playback switch failure shows a centered error surface with `Повторить` and `Назад`. Retry invokes session retry; Back follows normal player back logic. Resolver/provider failures must not be presented as successful playback.
