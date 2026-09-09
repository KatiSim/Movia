# Movia 0.9.32 — canonical design system

This document is the visual source of truth for the build installed on the phone on 2026-09-09. It describes the design actually implemented in source commit `35d1d9f82396eab7641359633740a642586eb254`, not a mock-up.

## 1. Design intent

Movia is dark-first and uses a restrained premium gold/slate language. The app deliberately avoids unrelated accent colors. Gold is reserved for active state, play intent, rating emphasis, focus/selection and progress. Ordinary containers remain graphite/slate so the interface does not become a field of gold rectangles.

Canonical source files:

- `android/app/src/main/java/app/movia/android/ui/theme/ColorTokens.kt`
- `android/app/src/main/java/app/movia/android/ui/theme/Theme.kt`
- `android/app/src/main/java/app/movia/android/ui/MoviaApp.kt`
- `android/app/src/main/java/app/movia/android/ui/home/HomeScreen.kt`
- `android/app/src/main/java/app/movia/android/ui/components/MediaCard.kt`
- `android/app/src/main/java/app/movia/android/ui/details/DetailsScreen.kt`
- `android/app/src/main/java/app/movia/android/ui/player/PlayerScreen.kt`
- `android/app/src/main/java/app/movia/android/ui/player/MiniPlayerBar.kt`

## 2. Canonical palette

| Token | Value | Use |
|---|---:|---|
| `MoviaBrandAmber` | `#D4AF37` | primary CTA, selected state, rating, played progress |
| `MoviaPrimaryAccentHover` | `#B8912A` | pressed primary CTA |
| `MoviaOnBrandAmber` | `#0E1015` | text/icons on solid gold |
| `MoviaGlowLuminescence` | `rgba(242,207,95,.28)` | ambient gold glow |
| `MoviaBorderSubtle` | `#2A2F3D` | ordinary 1 dp outlines/dividers |
| `MoviaBorderFocused` | `#D4AF37` at 40% | focused fields |
| canvas | `#0E1015` | application background |
| card | `#181B22` | normal surfaces |
| elevated | `#20232C` | controls and elevated surfaces |
| primary text | `#FFFFFF` | titles and important values |
| secondary text | `#A0A6B2` | metadata and descriptions |
| muted text | `#788191` | low-priority information |
| nav glass | `rgba(18,18,18,.82)` | one continuous bottom bar surface |
| nav top bevel | white at ~8% | one top border across bottom bar |
| scrim 40/60/70 | black 40/60/70% | artwork text protection |

Runtime is dark-only unless `themeMode=SYSTEM`; the light aliases intentionally map back into the same palette family so accidental use does not introduce a second brand language.

## 3. Movia identity and logo

### Launcher artwork

The active adaptive launcher icon uses `drawable-nodpi/movia_launcher_art.png` as the complete background artwork and a transparent adaptive foreground.

- source: `android/app/src/main/res/drawable-nodpi/movia_launcher_art.png`
- dimensions: `1024 × 1024`, RGBA
- SHA-256: `7e1f00cbb0cb790cf86680bb3a14709f0ed10bf187a59235a529e72514739af3`
- adaptive XML: `mipmap-anydpi-v26/ic_launcher.xml` and `mipmap-anydpi-v33/ic_launcher.xml`

`ic_movia_foreground.xml` remains a vector identity fallback: a rounded V path `M27,29 L54,78 L81,29`, 12-unit stroke, gold `#D4AF37`.

### Home wordmark

The word `Movia` on Home is not a flat color. It is a 31 sp bold sans-serif horizontal gradient:

- 0–10% `#D4AF37`
- 20% `#DCC165`
- 30% `#E2C562`
- 40% `#E8D081`
- 50% `#EFE2BA`
- 60% `#F3EAD1`
- 70% `#F8F4E6`
- 80–100% white

Line height is 34 sp and letter spacing is 0.15 sp. This gradient is the canonical premium logo treatment inside the app.

## 4. Geometry language

Movia uses soft, consistent geometry rather than sharp table-like cells.

- ordinary media poster: 14 dp radius, 1 dp `MoviaBorderSubtle`
- Home hero: 16 dp radius, 1.5 dp gold border
- Details hero: bottom corners 24 dp
- Details fallback poster: 18 dp radius
- primary Watch CTA: 10 dp radius
- secondary Details action: 12–14 dp
- episode row: 12–14 dp
- player glass action: circle, 44 dp diameter
- central player control: circle with translucent gold outline
- search field: 16 dp radius
- settings cards: 14–18 dp
- person avatar: circle, 72 dp in rows / 104 dp on person page

Borders are normally 1 dp. Gold borders indicate selected/focused/primary interactive context; graphite borders indicate structure.

## 5. Gold outlines and glow system

Gold is not used as a rectangular background everywhere. The hierarchy is:

1. **Structural outline:** `#2A2F3D`, 1 dp.
2. **Focused/selected outline:** `#D4AF37`, often 40–100% alpha depending on hierarchy.
3. **Ambient glow:** `#F2CF5F` with low alpha and blur/radial falloff.
4. **Solid gold fill:** only high-confidence CTA/selected chips/progress.

Examples in the installed design:

- Home hero outer border: 1.5 dp gold, ~92% idle and ~72% pressed.
- Home hero ambient glow: unbounded blur 18 dp idle / 10 dp pressed, gold alpha ~36% / 22%.
- Home central Play surface: 1 dp gold at ~92%, local blurred circular glow.
- Player central control: 1 dp gold at 48%; radial gold center fades into graphite.
- Selected quality/voice chips: gold 1 dp outline + gold fill at 14–16%.
- Active episode row: gold outline, subtle gold background.
- Active media card in franchise row: poster border turns gold.
- Focused catalog/search input: gold focus outline; cursor gold.

## 6. Home screen

### Header

The Home header is intentionally minimal. The primary identity is the gradient `Movia` wordmark. The app does not place a heavy solid header behind it.

### Continue Watching / hero card

The hero uses a 16:9 real backdrop when available. Visual stack from back to front:

1. artwork;
2. 12% atmospheric dark wash;
3. long vertical floor fade from transparent to nearly opaque `#0E1015`;
4. 18 dp blurred haze in the lower 116 dp to protect title/progress readability;
5. gold-outline status badge at top-left;
6. center circular Play button with local gold glow;
7. title + metadata at bottom-left;
8. progress track at the bottom.

The whole card scales to `0.97` while pressed and has no default rectangular ripple.

The progress line uses `MoviaProgressTrack` for the unplayed area and solid brand gold for played progress. A real timeline gets a gold circular thumb.

### Horizontal media shelves

Sections use `LazyRow`, 12 dp gaps, and 48 dp trailing breathing room. Ordinary cards use the shared `MediaContentCard`.

## 7. Ordinary media card

Canonical hierarchy:

1. poster, 2:3 ratio;
2. title, semibold, max two lines;
3. metadata line: gold rating + year + country + main genre/type.

The poster has a 14 dp radius and 1 dp graphite border. If a playable source is known, a restrained top-left playback badge is rendered over the poster. Missing metadata is not fabricated: absent year/country/duration/quality stays absent.

Card identity is stable by `mediaId`; late enrichment may update fields of the same item but must not swap the card to a different title.

## 8. Details screen

### Hero

Portrait Details hero height is about 42% of screen height; landscape uses about 58%.

- with real backdrop: crop it across the hero;
- without backdrop but with poster: create a blurred enlarged poster background at alpha 34%, overlay a broad gold radial glow, then place one correctly fitted 2:3 poster in front;
- no artwork: use the dark hero placeholder.

A vertical scrim fades the lower hero into the app canvas. The hero and top area support intentional swipe-down back.

### Identity and metadata

Title: 26 sp bold, max two lines.

Metadata is split into two deliberate rows:

- row 1: `★ rating • year • country • primary genre`
- row 2 for series: `N seasons • N episodes • minutes/episode`
- row 2 for movie: `Film • minutes`
- live TV uses its type and `Прямой эфир`.

Rating is gold; the rest of row 1 is primary text; row 2 uses secondary text. Unknown facts are omitted instead of replaced with defaults.

### Main Watch button

Full width, gold `#D4AF37`, dark text/icon, 10 dp radius. Height is 56 dp with one line or 68 dp when a secondary resume/episode line is present. Press state scales to `0.98` and changes fill to `#B8912A`.

### Season/episodes entry

56 dp high graphite button, 14 dp radius, 1 dp subtle border, playlist icon left and chevron right. It opens the dedicated season sheet/screen.

### Quick actions

Download and Share are equal-width 64 dp surfaces. Normal state uses graphite outline and secondary text. Active Download uses gold icon/text/border.

### Synopsis

Body text is clamped to three lines until `Подробнее` is pressed. Expand/collapse link is gold.

### Cast / creator / director

People are first-class entities, not plain text:

- actor/director/creator row avatar: 72 dp circle;
- graphite circular background, 1 dp subtle outline;
- real `profile_path` photo when present;
- gold initial fallback when photo is unavailable;
- name under avatar, role below in secondary text.

Movie uses `Режиссёр`. Series uses `Создатели`. Tapping any person opens a person page with 104 dp avatar, localized department and a two-column Movia filmography grid.

## 9. Season and episode selector

The current selector intentionally has **no left back arrow and no right close button**. The top interaction affordance is a centered 36 × 4 dp drag handle at 40% secondary-text alpha.

- swipe down on the header closes;
- horizontal swipes belong only to season paging;
- reaching first/last season never closes the window;
- selected season is kept visible by scrolling the season `LazyRow`;
- selected season chip uses solid gold background and dark text;
- ordinary chips use graphite surface + subtle border;
- episode rows use 12/14 dp geometry and gold selection treatment.

## 10. Bottom navigation bar

The phone navigation has exactly three destinations:

- `Главная`
- `Каталог`
- `Моё`

The bar is **one continuous surface**, not three rectangles.

- full-width background: `MoviaNavGlassSurface = rgba(18,18,18,.82)`;
- one continuous top bevel: white ~8%;
- no per-tab background;
- no `NavigationBarItem` indicator rectangle;
- no bounded rectangular ripple (`indication = null`);
- active icon/text: brand gold;
- inactive icon/text: `onSurfaceVariant`;
- active icon gets a 56 dp radial gold glow with multiple alpha falloffs;
- the 64 dp control row stays bottom-anchored; panel extends 4 dp upward plus system navigation inset.

Behind it, on Android 12+, a RenderNode blur with ~40 px radius replaces sharp content in the bar zone before the glass tint is painted. Therefore underlying cards cannot create vertical seams or different tab-colored blocks.

## 11. Mini-player

The in-app mini-player is 76 dp high:

- artwork crop 82 × 52 dp, 10 dp radius;
- title up to two lines;
- Play/Pause action;
- Close playback action;
- 2 dp gold progress line at the bottom.

Tapping the body reopens the full player.

## 12. System media notification

Movia uses Android Media3 `MediaSessionService`.

- title comes from current `MediaItem`;
- poster is supplied as media artwork;
- Android/HyperOS may derive its own ambient notification color from that poster;
- Play/Pause is synchronized with the actual Player;
- `sessionActivity` is an explicit PendingIntent to `app.movia.android/.MainActivity`, so tapping the media card returns to Movia rather than whichever app happened to be foreground.

The OS owns the exact lock-screen/notification layout; Movia supplies metadata, artwork and media actions.

## 13. Full player visual language

The player uses a static cinema backdrop rather than mirroring the movie frame into the entire screen. Stack:

- deep dark vertical surface gradient;
- broad radial gold/white luminescence behind video;
- secondary softer radial glow;
- transparent PlayerView over it;
- lower black scrims for timeline/control legibility.

The video itself remains visually clean while surrounding empty aspect-ratio space feels integrated.

### Central control

The central circle is the visual anchor. It has a translucent gold outline and radial graphite/gold fill. When buffering it contains the **only animated spinner**. The separate status pill is text-only, preventing two simultaneous spinners during quality/voice changes.

### Player top controls

Circular 44 dp glass actions with graphite translucent fill and 1 dp subtle border:

- Back;
- Lock;
- Picture-in-Picture;
- Settings.

For series, title/context can also open episodes.

### Timeline

- elapsed time left;
- rounded track in center;
- unplayed graphite, buffered translucent white, played solid gold;
- white 14 dp thumb, 18 dp while scrubbing;
- scrubbing adds a soft gold halo;
- remaining time right;
- series episode shortcut when applicable;
- fullscreen action.

Scrub preview is 120 × 68 dp, 10 dp radius, subtle outline.

## 14. Player settings

Settings are a full dark sheet with drag handle and swipe-down close. Main sections:

- `КАЧЕСТВО ВИДЕО`
- `ОЗВУЧКА РЕЛИЗА`
- `УПРАВЛЕНИЕ И ПЕРЕХОДЫ`

Selected chips: gold text/border + 16% gold fill. Unselected chips: graphite surface + subtle outline. Toggles use gold track when checked.

Quality and voice lists come from actual resolved stream/Media3 state; stale provider claims are not supposed to become fake selectable quality.

## 15. Player gesture language

- single tap: show/hide player chrome;
- repeated side taps: ±10 second seek chain;
- left vertical gesture: brightness;
- right vertical gesture: volume;
- center vertical swipe-down in portrait: leave player;
- Back closes nested settings/episodes first, exits landscape next, exits playback last;
- Lock hides controls until explicitly unlocked.

Gesture zones exclude top/bottom chrome so system/navigation controls do not fight with brightness/volume gestures.

## 16. Search/catalog controls

Search and catalog inputs use 16 dp rounded surfaces, 1 dp subtle outline and gold focused outline/cursor. Filter controls use 10–12 dp radii. Selected filter chips use gold fill or gold outline depending on hierarchy. The grid uses stable item IDs.

Catalog scroll state is hoisted above Details. Opening a media card must not recreate the grid at item 0; returning from Details resumes the same `LazyGridState` and route snapshot.

## 17. Accessibility rules encoded in source

- important controls have content descriptions;
- media cards merge descendant semantics into one readable description;
- selected chips expose selected semantics;
- timeline exposes progress semantics and programmatic `setProgress`;
- disabled previous/next episode controls remain visually present at reduced alpha instead of disappearing.

## 18. What not to change accidentally

The following are baseline invariants:

- do not introduce random accent colors;
- do not give bottom tabs individual rectangle backgrounds;
- do not restore back/close duplication in season sheets where drag handle is the close affordance;
- do not show two buffering spinners at once;
- do not fabricate missing metadata;
- do not route Details by title alone when `mediaId` exists;
- do not replace real artwork with placeholders when a valid URL exists;
- do not make gold the default background for ordinary cards.
