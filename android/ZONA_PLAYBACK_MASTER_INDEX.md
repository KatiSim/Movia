# Zona playback master index for Movia

Status: inventory completed from `~/projects/zona-reference-20260829-223618` on 2026-08-31.

This document records the reusable playback architecture and the boundary of
what is safe to execute in Movia. Zona is a reference for state machines and
provider contracts only. It is not a second catalog, source of Movia cards, or
a place to copy credentials from. The Movia `catalog.db` remains the only
catalogue SSOT.

The JADX snapshot has 25,480 Java files, 91 methods with decompilation errors,
324 indexed Zona-owned playback/content components, and 50 registry entries.
The saved reference has no complete smali tree. `player_script.js` is an
embedded web-player helper; `libzona.so` exposes utility/time JNI functions,
not the core Media3 state machine. Where JADX is incomplete, the component is
marked as an architectural observation rather than byte-for-byte source.

## End-to-end contract

```text
Movia catalog card (canonical mediaId/title/year/type)
  -> exact Zona content lookup / provider identity
  -> VideoSourcesServiceWithCache (logical source refs)
  -> StreamsProvider registry by videoSourceTypeId
  -> parallel StreamExtractorAdapter flows
  -> incremental batches + isolated errors
  -> UniqueStreamFilter
  -> AllStreamsHandler
  -> problem-aware BEST/BETTER ranking
  -> selected StreamInfo-compatible candidate
  -> PlayerPrepareComponent
  -> provider-supplied URL/UA/headers/reloadData DataSource
  -> Media3 ExoPlayer
  -> READY + position movement
```

An extractor failure is a source-level failure. It must not terminate the
global fan-out. A source without an exact content identity is rejected. A
missing source-ref is `NO_SOURCE`/`UNMATCHED`, never a fabricated key.

## Component index

| CLASS | ROLE | INPUT | OUTPUT | CALLERS | NETWORK ENDPOINTS | CACHE | TTL | ERROR HANDLING | RELATED EXTRACTOR ID |
|---|---|---|---|---|---|---|---|---|---|
| `ru.zona.app.stream.PlaybackParams` (`p268Of/C4548j.java`) | Immutable playback request identity | `entityId`, exact `episodeNum`, trailer flag, session, attempt | Request state key | `PlayerService`, `MovieStreamService`, `PlayerStreamService` | None directly | In-memory state | Session lifetime | Stale generations are ignored/cancelled | All |
| `ru.zona.api.stream.VideoSource` (`p210Lb/C3912Z.java`) | Logical provider source-ref | id, `video_source_type_id`, `video_content_type_id`, `kinopoisk_id`, `download_link_key`, `episode_key`, info | One source descriptor, not a URL | `VideoSourcesServiceWithCache`, `StreamsProvider` | Protected source lookup result | Source response cache | 1 hour | Missing extractor/ref is explicit source error | All |
| `ru.zona.api.stream.StreamInfo` (`p210Lb/C3892E.java`) | Full playable variant DTO | VideoSource plus extractor response | 22-field stream object | `StreamExtractorAdapter`, player, settings | Provider-specific extractor calls | Candidate/session state | Until source refresh; source pool stale window observed at 10h | Invalid URL, provider failure, reload failure | All |
| `ru.zona.api.stream.StreamExtractorAdapter` (`p210Lb/C3940z.java`) | Callback-to-Flow adapter | One VideoSource and provider extractor | Incremental `StreamInfo` batches and errors | `StreamsProvider` | Provider-defined GET/POST/config routes | Provider config owned by adapter | Provider-defined; dynamic configs are refreshed | Late callbacks ignored after cancellation; errors are isolated | All |
| `ru.zona.api.stream.StreamsProvider` (`p210Lb/C3905S.java`) | Registry and parallel fan-out | List of VideoSources | Merged per-source flows | Movie/serial stream services | All selected provider routes | None beyond adapter/config caches | N/A | One adapter cannot fail the fan-out | All |
| `ru.zona.api.stream.UniqueStreamFilter` (`p210Lb/C3910X.java`) | Session dedupe | Stream batches | Unique variants | Fan-out collector | None | Session set | Playback session | Duplicate batches are dropped | All |
| `ru.zona.app.stream.AllStreamsHandler` (`p268Of/C4541c.java`, `C4542d.java`) | Incremental aggregate state | Expected source count, batches, errors | Answered count, candidates, terminal state | `MovieStreamService`, `PlayerStreamService` | None | StateFlow-like state | Session lifetime | Provider errors remain attached to source; empty global result is explicit | All |
| `ru.zona.app.stream.movie.MovieStreamService` (`p304Qf/C5209h.java`) | Movie/series orchestration | PlaybackParams | Source lookup, fan-out and reload state | `PlayerService`, player root | Content/source endpoints through services | Delegated caches | Source cache 1h; stale source refresh ~10h | Cancellation, no-source, targeted reload | All movie/series types |
| `ru.zona.app.stream.movie.MovieStreamService.tryReloadStream` (`p304Qf/C5212k.java`) | Same-logical-stream refresh | Failed StreamInfo and current params | Replaced StreamInfo with fresh URL/headers | Player error path | Owning extractor refresh route | Adapter/config cache | Provider-defined | Only matching source/type is accepted | All |
| `ru.zona.app.services.VideoSourcesServiceWithCache` (`p818uf/C18970Q.java`) | Logical source discovery/cache | provider content id, episode, trailer, user context | `List<VideoSource>` | Movie/serial services | Protected `getVideoSources` equivalent | Video-source cache | 1 hour | Mirror/failure returns source error, not URL | All |
| `ru.zona.app.services.EpisodeServiceWithCache` (`p818uf/C18989i.java`) | Exact episode lookup/cache | serial/entity and episode query | Episode list/selected episode | `PlayerService`, serial navigation | Episode/content routes | Episode cache | Provider/cache policy | Missing exact episode is rejected | Series |
| `ru.zona.app.services.player.PlayerService` (`p070Df/C1103c.java`) | Current/next/previous episode service | PlaybackParams | Exact episode and neighbour results | Player root, serial controls | Episode/content routes | Delegated cache | Provider/cache policy | Null/error neighbour result does not corrupt current state | Series |
| `ru.zona.app.components.player.PlayerPrepareComponent` (`p899zd/C21165C0.java`) | Prepare/ad/content player state machine | selected StreamInfo and optional ad config | Prepare, EntityPlayer, Error, VAST/Yandex config | Player component root | Optional ad routes; content source via DataSource | None | Session lifetime | Ad preparation failure does not discard content player | All |
| `ru.zona.app.components.player.EntityExoPlayer` (`p899zd/C21224p.java`) | Media3 playback | MediaItem, subtitles, start position | ExoPlayer state/position/tracks | Player component | Provider URL through DataSource | Media3 allocator/cache policy | Player lifetime | Error callback enters reload/fallback path | All |
| `ru.zona.app.components.player.ZonaDataSourceFactory` (`p899zd/C21175H0.java`) | Per-stream transport boundary | Stream transform/reload, UA, headers | Openable DataSource | EntityExoPlayer | Actual provider URL only | DataSource/HTTP | Per request | Transform before open; one reload/open retry | All |
| `ru.zona.app.components.player.settings.ChoiceQualityComponent` (`p086Ed/C1290a.java`) | Concrete quality selection | Available StreamInfo list and preference | Selected concrete variant | Player settings | None | User preference | User/session | No label-only change; unavailable quality remains unavailable | All |
| `ru.zona.app.components.player.settings.ChoiceStreamComponent` (`p104Fd/C2132c.java`) | Voice/source selection | Concrete stream list and preference | Selected logical source/variant | Player settings | None | User preference per entity/episode | User/session | Invalid/stale selection falls back through ranking | All |
| `ru.zona.app.components.player.StreamPlaybackNotifier` (`p899zd/C21171F0.java`) | Provider playback lifecycle | stream transitions and progress | Provider callback events | Entity player | Provider-specific telemetry when required | None | Session lifetime | Listener failures do not stop playback | Provider-specific |
| `mobi.zona.data.model.StreamInfo` (`mobi/zona/data/model/StreamInfo.java`) | Deprecated 12-field compatibility DTO | Legacy serialized response | Old stream representation | Legacy API code | Legacy response routes | Legacy cache | Provider policy | Kept for schema mapping only | Legacy |
| `mobi.zona.data.model.VideoSource` (`mobi/zona/data/model/VideoSource.java`) | Deprecated 7-field source DTO | Legacy source response | Old logical source | Legacy API code | Legacy source routes | Legacy cache | Provider policy | Kept for schema mapping only | Legacy |
| `ru.zona.utils.CppUtil` JNI / `libzona.so` | Utility/time/check helpers | Byte/string/time inputs | Utility results | Config/time/auth helpers | None by itself | None observed | N/A | No player logic inferred from exported symbols | Config/time |
| `assets/player_script.js` | Embedded web-player helper | WebView player commands | JS player events/errors | Embedded provider player | Provider WebView URL | WebView cache | WebView policy | Reports playback error/end to bridge | Web/embed |
| `RoomMovieCacheItem` / `movieCacheDao` | Metadata/cache persistence | Content DTO | Cached content | Content API/cache layer | None | Zona Room DB | Cache policy | Cache miss goes live | N/A |
| `RoomIdsCacheItem` / `idsCacheDao` | ID mapping cache | External/provider IDs | ID mapping | Content/source lookup | None | Zona Room DB | Cache policy | Miss goes live; no guessed mapping | N/A |
| `ParamEntity` / `paramsDao` | Dynamic parameter/config cache | Named config values | Config values | API/config providers | Dynamic config routes | Zona Room DB | Config-defined | Invalid config triggers refresh/error | Extractor configs |

## StreamInfo field preservation contract

Movia's `StreamOption`/`StreamCandidate` boundary must retain all fields below
until Media3 or the P2P gateway has consumed them. `null` means “not supplied by
the provider”; it must not be replaced with a guessed value.

| StreamInfo field | Movia transport field | Required behaviour |
|---|---|---|
| `videoSource` | source/provider/source type and provider content id | Keep logical source identity separate from URL. |
| `url` | `url` | Only structurally valid HTTP(S), file, or BTIH magnet values. |
| `translation` | `voice` | Preserve provider label; UI selects the candidate. |
| `language` | `language` | Used by ranking and diagnostics. |
| `quality` / `resolution` | `quality` / resolution | Real variants only; unavailable quality is not fabricated. |
| `subtitleList` | external subtitle list | Validate URLs and MIME/label metadata. |
| `isUseInternalSubtitles` | `hasInternalSubtitles` | Preserve independently from external subtitles. |
| `userAgent` / `headers` | per-stream request profile | Adapter supplies; player never derives Referer/Origin from hostname. |
| `unavailableQuality` | unavailable-quality metadata | Keep provider signal for UI/ranking. |
| `codec` | `codec` | Feed compatibility ranking/Media3. |
| `downloadUrl` / `downloadHeaders` | download transport profile | Keep separate from playback URL. |
| `skipIntervals` | skip metadata | Preserve milliseconds and validate bounds. |
| `videoTrackIndex` / `audioTrackIndex` | explicit track selectors | Do not infer when provider supplies them. |
| `advertisement` | ad metadata | Keep flag/metadata for preparation policy. |
| `reloadData` | opaque provider-owned reload contract | Pass through without logging secret values. |
| `duration` / `size` | duration/size | Use as hints until Media3 reports authoritative values. |
| season/episode/file selector | request and P2P selector | Exact SxxEyy and file index/path; never `largestFile()`. |

## Cache and refresh boundaries

| Movia cache | Contents | TTL/invalidation |
|---|---|---|
| Catalog metadata cache | Catalog cards only | Catalog policy; source changes do not replace cards. |
| Source-discovery cache | Exact provider content id + episode + trailer + user context -> VideoSource refs | Zona observation: 1 hour; invalidate on forced refresh or identity mismatch. |
| Stream-candidate cache | Sanitized concrete candidates and full transport metadata | Short-lived for signed direct URLs; refresh URL/reloadData before changing logical variant. |
| P2P media cache | Torrent metadata, selected file, pieces, resume state | Separate from candidate cache; invalidate on hash/file mismatch. |
| Dynamic-config cache | extN/listN/config data and mirror health | Config-defined short TTL; refresh on provider contract failure. |

The old Zona cache migration is complete and idempotent. No playback code may
restart it or append a second Zona catalogue. A cache hit is still passed
through the common identity and stream validator.

## Identity gate

Every request carries `mediaId`, media type, canonical title and year. Series
requests additionally carry season and episode. Provider lookup must return a
matching provider identity and source refs must carry that mapping forward.

```text
catalog card
  == requested media identity
  == provider content identity (title/year/type)
  == source identity (season/episode)
  == candidate identity
  == PlaybackRequest identity
```

Equality is exact after the shared text normalizer, with an explicit alias only
when it is already present on the same catalog card. Prefix matches and
“first search result” matches are not valid. Zero matches are `UNMATCHED`;
multiple possible cards are `AMBIGUOUS`; both are rejected.

## Extractor registry: all 50 entries

`IMPLEMENTED` means a safe in-process adapter exists and its deterministic
contract tests pass. `DOCUMENTED_BLOCKER` means the APK class and registry
entry are inventoried, but the current reference does not provide a safe,
authorized, testable contract to execute. Such an ID is handled explicitly as
a blocker; it must never fall through to a generic endpoint or a fake source.

| ID | APK class | Provider label | Status | Dynamic config / source-ref | Flow and blocker |
|---:|---|---|---|---|---|
| 1 | `tc/C18668a.java` | mobilink | IMPLEMENTED | `getMobiVideo`, source key | GET/JSON, LQ direct candidate, mirror/time retry |
| 2 | `ac/C7626e.java` | hdrezka | IMPLEMENTED | `ext2`, page/source key | page -> translator -> POST -> decode -> HLS/direct; dynamic decoder tested |
| 3 | `p426Xb/C6783o.java` | filmix | IMPLEMENTED | `ext3`, player/source key | dynamic player payload -> POST/direct; movie and series paths tested |
| 5 | `p282Pb/C4790l.java` | bazon | DOCUMENTED_BLOCKER | source key recovered | `RECAPTCHA_RSA_AES_TRANSFORMER_REQUIRED`; no bypass or fabricated key |
| 6 | `p103Fc/C2100m.java` | videocdn | IMPLEMENTED | `ext6`, source parts | config -> player payload -> HLS variants; XOR/config fixtures tested |
| 7 | `p591hc/C12352b.java` | kinomania | IMPLEMENTED | page/source ref | page extraction, language/quality and malformed response tests |
| 8 | `p228Mb/C4125g.java` | alloha | IMPLEMENTED | `ext8` + endpoint map, download key | page -> JS/payload -> decrypt -> variants; series exact episode tested |
| 9 | `p264Ob/C4467c.java` | awmzone | IMPLEMENTED | `ext9` + endpoint map, source key | config -> player JS -> HLS; malformed/decoder fixtures tested |
| 11 | `p049Cc/C0683a.java` | ustore | DOCUMENTED_BLOCKER | source-ref shape inventoried | no safe live contract/fixture in current port; explicit blocker |
| 12 | `p770rc/C18302a.java` | lordfilms | DOCUMENTED_BLOCKER | source-ref shape inventoried | provider contract unavailable for authorized deterministic adapter |
| 13 | `p560fc/C11656a.java` | kholobok | DOCUMENTED_BLOCKER | source-ref shape inventoried | provider contract unavailable; no generic URL fallback |
| 14 | `p638jc/C15928a.java` | kinoteatr | IMPLEMENTED | page/source ref | page/direct extraction, identity and malformed response tests |
| 15 | `nc/C17377c.java` | kodik | DOCUMENTED_BLOCKER | source-ref shape inventoried | protected/session contract not safely available in current port |
| 16 | `p526dc/C10704b.java` | ru | DOCUMENTED_BLOCKER | source-ref shape inventoried | provider/auth contract not safely available |
| 17 | `mc/C17150b.java` | kinovod | DOCUMENTED_BLOCKER | source-ref shape inventoried | provider contract unavailable; no fabricated URL |
| 19 | `p300Qb/C5153b.java` | cdnmovies | DOCUMENTED_BLOCKER | shared registry contract | provider contract unavailable; shared ID implementation not safe to assume |
| 20 | `p300Qb/C5153b.java` | cdnmovies | DOCUMENTED_BLOCKER | shared registry contract | same shared implementation as 19; explicit until contract fixture exists |
| 21 | `ec/C11258b.java` | ivi-movie | DOCUMENTED_BLOCKER | source-ref shape inventoried | protected/authenticated provider contract unavailable |
| 22 | `ec/C11260d.java` | ivi-movie | DOCUMENTED_BLOCKER | source-ref shape inventoried | protected/authenticated provider contract unavailable |
| 23 | `p717oc/C17536a.java` | krasview | DOCUMENTED_BLOCKER | source-ref shape inventoried | provider contract unavailable |
| 24 | `p211Lc/C3944d.java` | zagonka | DOCUMENTED_BLOCKER | source-ref shape inventoried | provider contract unavailable |
| 25 | `p229Mc/C4141c.java` | zetflix | DOCUMENTED_BLOCKER | source-ref shape inventoried | provider contract unavailable |
| 26 | `p610ic/C13484b.java` | kinoplay | DOCUMENTED_BLOCKER | source-ref shape inventoried | provider contract unavailable |
| 27 | `vc/C19214a.java` | playep | DOCUMENTED_BLOCKER | source-ref shape inventoried | provider contract unavailable |
| 28 | `p193Kc/C3561b.java` | voidboost | DOCUMENTED_BLOCKER | source-ref shape inventoried | provider/session contract unavailable; player must not infer host headers |
| 29 | `p013Ac/C0117b.java` | thefilm | DOCUMENTED_BLOCKER | source-ref shape inventoried | provider contract unavailable |
| 30 | `p246Nb/C4271b.java` | anwap | DOCUMENTED_BLOCKER | source-ref shape inventoried | provider contract unavailable |
| 31 | `p175Jc/C3188c.java` | vk | DOCUMENTED_BLOCKER | source-ref shape inventoried | account/auth or provider contract unavailable |
| 32 | `p898zc/C21157g.java` | takedwn | DOCUMENTED_BLOCKER | source-ref shape inventoried | provider contract unavailable |
| 33 | `p318Rb/C5331b.java` | cdnvideohub | IMPLEMENTED | playlist/fallback source key | playlist -> quality variants; retry and malformed response tested |
| 34 | `p494bc/C8505c.java` | hdvb | DOCUMENTED_BLOCKER | source-ref shape inventoried | provider contract unavailable |
| 35 | `p390Vb/C6178b.java` | fancdn | IMPLEMENTED | `ext35`, source key | dynamic config -> playlists/subtitles; quality/headers tested |
| 36 | `p444Yb/C7042b.java` | filmru | IMPLEMENTED | page/source ref | page -> direct media, language/quality tests |
| 37 | `p139Hc/C2663f.java` | videoframe2 | DOCUMENTED_BLOCKER | source-ref shape inventoried | provider contract unavailable |
| 38 | `sc/C18485c.java` | cloud-mail | DOCUMENTED_BLOCKER | source-ref shape inventoried | account/session contract unavailable |
| 39 | `p882yc/C20730a.java` | sooplive | IMPLEMENTED | station/video source key | POST JSON -> media variants; retry/malformed tests |
| 40 | `p157Ic/C2932d.java` | videoseed | DOCUMENTED_BLOCKER | source-ref shape inventoried | provider contract unavailable |
| 41 | `p031Bc/C0411c.java` | turbo | DOCUMENTED_BLOCKER | source-ref shape inventoried | provider contract unavailable |
| 42 | `p864xc/C19949b.java` | rutube | IMPLEMENTED | player/options source key | API JSON -> HLS/direct variants; malformed/quality tests |
| 43 | `p847wc/C19716a.java` | plvideo | IMPLEMENTED | video API/source key | JSON -> master playlist -> quality/subtitles; exact series filtering tested |
| 44 | `p751qc/C18111i.java` | lomont | DOCUMENTED_BLOCKER | source-ref shape inventoried | provider contract unavailable |
| 45 | `p085Ec/C1289a.java` | veoveo | IMPLEMENTED | catalog episode source key | exact season/episode -> episode variants; naming tests |
| 46 | `p815uc/C18930a.java` | ok | IMPLEMENTED | page/source ref | page -> playlist/direct variants; quality tests |
| 47 | `p462Zb/C7275d.java` | flixcdn | DOCUMENTED_BLOCKER | source-ref shape inventoried | provider contract unavailable |
| 48 | `lc/C16773b.java` | kinovibe | DOCUMENTED_BLOCKER | source-ref shape inventoried | provider contract unavailable |
| 49 | `p737pc/C17829b.java` | link | IMPLEMENTED | `LinkData.download_link_key` | only an already-authoritative playable URL is accepted |
| 50 | `p655kc/C16255b.java` | kinoton | DOCUMENTED_BLOCKER | source-ref shape inventoried | provider contract unavailable |
| 51 | `p577gc/C11896b.java` | kinobadi | IMPLEMENTED | page/source ref | page -> direct candidates, exact series naming tested |
| 52 | `p408Wb/C6409h.java` | fanserials | DOCUMENTED_BLOCKER | account/config DTO inventoried | account/private session condition not safely available |
| 53 | `p121Gc/C2368c.java` | videodb | DOCUMENTED_BLOCKER | source-ref shape inventoried | provider contract unavailable |

The registry is intentionally sparse in numeric space: IDs 4, 10 and 18 are
not entries in the APK registry. They are not silently promoted to extractor
types. The complete set above is the authoritative set of 50 entries.

## Provider request and credential boundary

The adapter, not the player, owns URL, User-Agent, headers, Referer, Origin,
cookies schema, timestamps/nonces, signatures, decoder and reload data. Reports
may say `Authorization: [REDACTED]`, `Cookie: [REDACTED]`, or
`token: [REDACTED]`; this document contains no secret value. A short-lived
authorization may be obtained dynamically only through the provider's normal
authorized route. A missing permanent credential is
`CREDENTIAL_REISSUE_REQUIRED`, never a fabricated value.

## P2P contract

The torrent path is a separate transport adapter:

```text
BTIH source-ref -> metadata -> exact file selector -> first-piece priority
  -> sequential prebuffer -> local HTTP gateway -> Media3
  -> continued download, seek reprioritization, resume and health updates
```

For series, file selection must match `S03E05`, `3x05`, or an equivalent
season-3 episode-5 naming pattern. An episode-only match or largest-file choice
is rejected. Trackers may change without changing the variant identity:
`BTIH + file index/path + voice + quality + season + episode`.

## Acceptance evidence

`PLAYABLE` is a runtime claim only when Media3 has reached `READY`,
`playWhenReady`/`isPlaying` are true, and position moves in two observations.
`operation COMPLETED` alone is insufficient. The full gate also requires exact
identity, concrete voice/quality switching, same-stream reload, bounded
fallback, P2P sequential playback, and no duplicate cards. A timeout is a
failure. Release/tag/push is allowed only after those gates have real device
evidence.
