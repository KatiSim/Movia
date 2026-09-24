# ADR-297: Home Spotlight: Title Font Size (-5sp), Favorite Heart Action, Circular Ripple Bounds, and 35% Card Dimming

## Status
Accepted (CANONICAL)

## Context
In the Home Spotlight carousel (`HomeScreen.kt`), targeted UI refinements were required:
1. Active Spotlight media item title `fontSize` was `28.sp`, dominating the screen vertically and crowding the metadata row and CTA. The target is a reduction by exactly 5sp to `23.sp`.
2. The secondary circular button adjacent to the "Смотреть" CTA previously displayed bookmark icons (`Icons.Filled.Bookmark` / `Icons.Outlined.BookmarkBorder`) with bookmark semantics, despite binding to the favorite domain model (`onToggleFavorite`, `favorites: Set<String>`). The target is replacing bookmark icons with favorite hearts (`Icons.Filled.Favorite` / `Icons.Outlined.FavoriteBorder`) and corresponding accessibility semantics.
3. The circular button had a rectangular press state artifact: `Modifier.size(56.dp).clickable { ... }` on `Surface` produced a 56x56dp square ripple indication outside the circle boundaries.
4. Spotlight side card dimming was initially 28%, then calibrated to 22%, then 27%, and now calibrated to exactly 35% (alpha = 0.35). The target is exactly 0% dimming (alpha = 0.00) on the settled active center card, exactly 35% dimming (alpha = 0.35) on settled adjacent cards, and continuous linear interpolation during swipe without jumps or blur.

## Decision
1. **Title Font Size**:
   - Decreased `fontSize` from `28.sp` to `23.sp` in `HomeScreen.kt` active item title `Text`.
   - Preserved `fontWeight = FontWeight.Bold`, `lineHeight = 32.sp`, `maxLines = 2`, `overflow = TextOverflow.Ellipsis`, and `textAlign = TextAlign.Center`.
2. **Favorite Heart Icon & Semantics**:
   - Replaced `Bookmark` / `BookmarkBorder` with `Icons.Filled.Favorite` and `Icons.Outlined.FavoriteBorder`.
   - Updated `contentDescription` to `"Удалить из избранного"` / `"Добавить в избранное"`.
   - Retained existing favorite state and toggle logic (`onToggleFavorite(title, !isFavorite)`).
3. **Circular Press State & Ripple Bounds**:
   - Moved click handling into `Surface(onClick = { ... }, shape = CircleShape)`.
   - Added `Modifier.size(56.dp).clip(CircleShape)` to strictly bound all interaction feedback (press, focus, ripple, hover) to the circular silhouette.
4. **Side Card Dimming (35%)**:
   - Changed overlay alpha calculation to `val overlayAlpha = (0.35f * t).coerceIn(0.00f, 0.35f)`, where `t = abs(pageOffset).coerceIn(0f, 1f)`.
   - Settled active center card ($t = 0$): `alpha = 0.00` (0%).
   - Settled adjacent side cards ($t = 1$): `alpha = 0.35` (35%).
   - Continuous linear transition during horizontal swipe. Neutral black overlay (`Color.Black.copy(alpha = overlayAlpha)`), zero blur or color shift.

## Consequences
- Spotlight header has improved vertical rhythm and proportional balance.
- Favorite button visual iconography perfectly matches the domain model and details screen.
- Ripple and touch feedback are strictly contained within `CircleShape`.
- Side cards recede gently into the background with exactly 35% dimming while remaining legible.
