package app.movia.android.ui.components

import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.movia.android.ui.theme.MoviaBackgroundPrimary
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import kotlin.math.abs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class MoviaAmbientStrength(
    val primaryAlpha: Float,
    val secondaryAlpha: Float,
    val centerY: Float,
) {
    HOME(primaryAlpha = 0.7665f, secondaryAlpha = 0.735f, centerY = 0.28f),
    CATALOG(primaryAlpha = 0.11f, secondaryAlpha = 0.06f, centerY = 0.24f),
    MY(primaryAlpha = 0.34f, secondaryAlpha = 0.26f, centerY = 0.22f),
}

private data class AmbientPalette(
    val primary: Color,
    val secondary: Color,
    val tertiary: Color,
) {
    companion object {
        val Transparent = AmbientPalette(Color.Transparent, Color.Transparent, Color.Transparent)
    }
}

private val ambientPaletteCache = object : LinkedHashMap<String, AmbientPalette>(96, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, AmbientPalette>?): Boolean = size > 96
}

/**
 * Decorative, measurement-neutral ambient light derived from the active artwork.
 * The stable page foundation remains MaterialTheme background; failures resolve to transparent.
 *
 * Ambient Glow V4:
 * - Color A (primary): Bottom-Left behind active poster (30% width / 68% height, alpha = 0.73)
 * - Color B (secondary): Top-Right behind active poster (70% width / 32% height, alpha = 0.70)
 * - Elliptical shape (baseRadius = 210dp, scaleX = 1.15, scaleY = 0.80), smooth multi-stop cloud falloff.
 */
@Composable
fun Modifier.moviaAmbient(
    artworkUrl: String?,
    strength: MoviaAmbientStrength,
    cacheKey: String? = artworkUrl,
    posterLeftDp: Float = -1f,
    posterTopDp: Float = 112f,
    posterWidthDp: Float = 188f,
    posterHeightDp: Float = 282f,
): Modifier {
    val context = LocalContext.current.applicationContext
    var previous by remember { mutableStateOf(AmbientPalette.Transparent) }
    var current by remember { mutableStateOf(AmbientPalette.Transparent) }
    val transition = remember { Animatable(1f) }
    val drift = rememberInfiniteTransition(label = "MoviaAmbientDrift")
    val primaryDrift by drift.animateFloat(
        initialValue = -5f,
        targetValue = 5f,
        animationSpec = infiniteRepeatable(tween(7600), RepeatMode.Reverse),
        label = "PrimaryAmbientDrift",
    )
    val secondaryDrift by drift.animateFloat(
        initialValue = 6f,
        targetValue = -6f,
        animationSpec = infiniteRepeatable(tween(8800), RepeatMode.Reverse),
        label = "SecondaryAmbientDrift",
    )

    LaunchedEffect(artworkUrl, cacheKey) {
        val key = cacheKey?.takeIf { it.isNotBlank() } ?: artworkUrl
        val cached = key?.let { synchronized(ambientPaletteCache) { ambientPaletteCache[it] } }
        val next = cached ?: if (artworkUrl.isNullOrBlank()) {
            AmbientPalette.Transparent
        } else {
            val bitmap = loadAmbientArtwork(context, artworkUrl)
            if (bitmap == null) AmbientPalette.Transparent
            else withContext(Dispatchers.Default) { extractAmbientPalette(bitmap) }
        }
        if (cached == null && key != null) synchronized(ambientPaletteCache) { ambientPaletteCache[key] = next }
        previous = current
        current = next
        transition.snapTo(0f)
        transition.animateTo(
            targetValue = 1f,
            animationSpec = tween(
                durationMillis = 320,
                easing = CubicBezierEasing(0f, 0f, 0.2f, 1f),
            ),
        )
        previous = AmbientPalette.Transparent
    }

    val fraction = transition.value
    return drawBehind {
        fun drawPalette(palette: AmbientPalette, opacity: Float) {
            if (opacity <= 0f) return

            val pLeft = if (posterLeftDp >= 0f) posterLeftDp.dp.toPx() else (size.width - posterWidthDp.dp.toPx()) / 2f
            val pTop = posterTopDp.dp.toPx()
            val pWidth = posterWidthDp.dp.toPx()
            val pHeight = posterHeightDp.dp.toPx()

            val primaryCenter = Offset(
                x = pLeft + pWidth * 0.30f + primaryDrift.dp.toPx(),
                y = pTop + pHeight * 0.68f + primaryDrift.dp.toPx() * 0.35f,
            )
            val secondaryCenter = Offset(
                x = pLeft + pWidth * 0.70f + secondaryDrift.dp.toPx(),
                y = pTop + pHeight * 0.32f - secondaryDrift.dp.toPx() * 0.35f,
            )
            val tertiaryCenter = Offset(
                x = pLeft + pWidth * 0.50f,
                y = pTop + pHeight * 0.52f,
            )

            val baseRadius = when (strength) {
                MoviaAmbientStrength.HOME -> 264.6.dp.toPx()
                MoviaAmbientStrength.MY -> 254.dp.toPx()
                else -> 210.dp.toPx()
            }
            val scaleY = if (strength == MoviaAmbientStrength.MY) 1.00f else 0.80f

            // Color A: Primary (Bottom-Left)
            if (palette.primary.alpha > 0f) {
                scale(scaleX = 1.15f, scaleY = scaleY, pivot = primaryCenter) {
                    drawCircle(
                        brush = Brush.radialGradient(
                            colorStops = arrayOf(
                                0.00f to palette.primary.copy(alpha = strength.primaryAlpha * opacity),
                                0.25f to palette.primary.copy(alpha = strength.primaryAlpha * opacity * 0.86f),
                                0.50f to palette.primary.copy(alpha = strength.primaryAlpha * opacity * 0.54f),
                                0.72f to palette.primary.copy(alpha = strength.primaryAlpha * opacity * 0.20f),
                                0.88f to palette.primary.copy(alpha = strength.primaryAlpha * opacity * 0.05f),
                                1.00f to Color.Transparent,
                            ),
                            center = primaryCenter,
                            radius = baseRadius,
                        ),
                        radius = baseRadius,
                        center = primaryCenter,
                    )
                }
            }

            // Color B: Secondary (Top-Right)
            if (palette.secondary.alpha > 0f) {
                scale(scaleX = 1.15f, scaleY = scaleY, pivot = secondaryCenter) {
                    drawCircle(
                        brush = Brush.radialGradient(
                            colorStops = arrayOf(
                                0.00f to palette.secondary.copy(alpha = strength.secondaryAlpha * opacity),
                                0.25f to palette.secondary.copy(alpha = strength.secondaryAlpha * opacity * 0.86f),
                                0.50f to palette.secondary.copy(alpha = strength.secondaryAlpha * opacity * 0.54f),
                                0.72f to palette.secondary.copy(alpha = strength.secondaryAlpha * opacity * 0.20f),
                                0.88f to palette.secondary.copy(alpha = strength.secondaryAlpha * opacity * 0.05f),
                                1.00f to Color.Transparent,
                            ),
                            center = secondaryCenter,
                            radius = baseRadius,
                        ),
                        radius = baseRadius,
                        center = secondaryCenter,
                    )
                }
            }


            // Color C: Tertiary (centered depth layer), MY only.
            if (strength == MoviaAmbientStrength.MY && palette.tertiary.alpha > 0f) {
                scale(scaleX = 1.08f, scaleY = 1.00f, pivot = tertiaryCenter) {
                    drawCircle(
                        brush = Brush.radialGradient(
                            colorStops = arrayOf(
                                0.00f to palette.tertiary.copy(alpha = 0.18f * opacity),
                                0.34f to palette.tertiary.copy(alpha = 0.075f * opacity),
                                0.64f to palette.tertiary.copy(alpha = 0.035f * opacity),
                                0.84f to palette.tertiary.copy(alpha = 0.010f * opacity),
                                1.00f to Color.Transparent,
                            ),
                            center = tertiaryCenter,
                            radius = baseRadius,
                        ),
                        radius = baseRadius,
                        center = tertiaryCenter,
                    )
                }
            }
        }

        drawPalette(previous, 1f - fraction)
        drawPalette(current, fraction)

        // Softly dissolve to BackgroundPrimary at the bottom boundary (no visible seam)
        if (strength == MoviaAmbientStrength.HOME && size.height > 0f) {
            val fadeHeight = minOf(size.height * 0.22f, 48.dp.toPx())
            if (fadeHeight > 0f) {
                drawRect(
                    brush = Brush.verticalGradient(
                        colorStops = arrayOf(
                            0.00f to Color.Transparent,
                            0.35f to Color(0x33080B11),
                            0.70f to Color(0xAA080B11),
                            1.00f to MoviaBackgroundPrimary,
                        ),
                        startY = size.height - fadeHeight,
                        endY = size.height,
                    ),
                    topLeft = Offset(0f, size.height - fadeHeight),
                    size = Size(size.width, fadeHeight),
                )
            }
        }
    }
}

private suspend fun loadAmbientArtwork(context: android.content.Context, url: String): Bitmap? = try {
    val request = ImageRequest.Builder(context)
        .data(normalizeMoviaArtworkUrl(url))
        .size(64, 64)
        .allowHardware(false)
        .traceMoviaArtwork()
        .build()
    val result = SingletonImageLoader.get(context).execute(request)
    if (result is SuccessResult) result.image.toBitmap(64, 64) else null
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (_: Exception) {
    null
}

private data class HueBucket(
    var score: Float = 0f,
    var saturationWeighted: Float = 0f,
    var valueWeighted: Float = 0f,
)

private fun extractAmbientPalette(bitmap: Bitmap): AmbientPalette {
    if (bitmap.width <= 0 || bitmap.height <= 0) return AmbientPalette.Transparent

    val bucketsAll = Array(24) { HueBucket() }
    val bucketsBottomLeft = Array(24) { HueBucket() }
    val bucketsTopRight = Array(24) { HueBucket() }

    val targetSamples = 4096
    val totalPixels = bitmap.width.toLong() * bitmap.height.toLong()
    val stride = kotlin.math.sqrt((totalPixels.toDouble() / targetSamples).coerceAtLeast(1.0)).toInt().coerceAtLeast(1)
    val hsv = FloatArray(3)

    var y = stride / 2
    while (y < bitmap.height) {
        var x = stride / 2
        while (x < bitmap.width) {
            val pixel = bitmap.getPixel(x, y)
            AndroidColor.colorToHSV(pixel, hsv)
            val saturation = hsv[1]
            val value = hsv[2]

            // Reject near-black/white, low-chroma neutrals and extreme outliers
            if (value in 0.12f..0.92f && saturation >= 0.16f && !(saturation > 0.95f && value > 0.85f)) {
                val hue = hsv[0]
                val bucketIndex = ((hue / 360f) * bucketsAll.size).toInt().coerceIn(0, bucketsAll.lastIndex)
                val cinematicMidtone = (1f - abs(value - 0.55f) / 0.55f).coerceIn(0f, 1f)
                val weight = saturation * (0.45f + 0.55f * cinematicMidtone)

                bucketsAll[bucketIndex].apply {
                    score += weight
                    saturationWeighted += saturation * weight
                    valueWeighted += value * weight
                }

                // Sector A: Bottom-Left quadrant (x <= 65% width, y >= 40% height)
                if (x <= bitmap.width * 0.65f && y >= bitmap.height * 0.40f) {
                    bucketsBottomLeft[bucketIndex].apply {
                        score += weight
                        saturationWeighted += saturation * weight
                        valueWeighted += value * weight
                    }
                }

                // Sector B: Top-Right quadrant (x >= 35% width, y <= 60% height)
                if (x >= bitmap.width * 0.35f && y <= bitmap.height * 0.60f) {
                    bucketsTopRight[bucketIndex].apply {
                        score += weight
                        saturationWeighted += saturation * weight
                        valueWeighted += value * weight
                    }
                }
            }
            x += stride
        }
        y += stride
    }

    val rankedAll = bucketsAll.indices
        .filter { bucketsAll[it].score > 0f }
        .sortedByDescending { bucketsAll[it].score }

    if (rankedAll.isEmpty()) return AmbientPalette.Transparent

    val rankedBL = bucketsBottomLeft.indices
        .filter { bucketsBottomLeft[it].score > 0f }
        .sortedByDescending { bucketsBottomLeft[it].score }

    val rankedTR = bucketsTopRight.indices
        .filter { bucketsTopRight[it].score > 0f }
        .sortedByDescending { bucketsTopRight[it].score }

    // Color A (primary): preferred from Bottom-Left, fallback to overall primary
    val primaryIndex = rankedBL.firstOrNull() ?: rankedAll.first()
    val primary = normalizedBucketColor(primaryIndex, if (rankedBL.isNotEmpty()) bucketsBottomLeft else bucketsAll)

    // Color B (secondary): preferred from Top-Right with distinct hue, fallback to distinct hue from overall
    val secondaryIndex = rankedTR.firstOrNull { hueDistance(primaryIndex, it, bucketsAll.size) >= 2 }
        ?: rankedAll.firstOrNull { hueDistance(primaryIndex, it, bucketsAll.size) >= 3 }

    val secondary = if (secondaryIndex != null) {
        val useBuckets = if (rankedTR.any { it == secondaryIndex }) bucketsTopRight else bucketsAll
        normalizedBucketColor(secondaryIndex, useBuckets)
    } else {
        derivedSecondary(primary)
    }

    val tertiaryIndex = rankedAll.firstOrNull { candidate ->
        hueDistance(primaryIndex, candidate, bucketsAll.size) >= 2 &&
            (secondaryIndex == null || hueDistance(secondaryIndex, candidate, bucketsAll.size) >= 2)
    }
    val tertiary = tertiaryIndex?.let { normalizedBucketColor(it, bucketsAll) } ?: derivedTertiary(primary)

    return AmbientPalette(primary = primary, secondary = secondary, tertiary = tertiary)
}

private fun normalizedBucketColor(index: Int, buckets: Array<HueBucket>): Color {
    val bucket = buckets[index]
    if (bucket.score <= 0f) return Color.Transparent
    val hue = (index + 0.5f) * (360f / buckets.size)
    // Boosted saturation and value by ~20% for perceived brightness and contrast
    val saturation = (bucket.saturationWeighted / bucket.score * 1.15f).coerceIn(0.40f, 0.85f)
    val value = (bucket.valueWeighted / bucket.score * 1.15f).coerceIn(0.42f, 0.78f)
    return Color(AndroidColor.HSVToColor(floatArrayOf(hue, saturation, value)))
}

private fun derivedSecondary(primary: Color): Color {
    if (primary.alpha <= 0f) return Color.Transparent
    val hsv = FloatArray(3)
    AndroidColor.colorToHSV(
        AndroidColor.rgb((primary.red * 255).toInt(), (primary.green * 255).toInt(), (primary.blue * 255).toInt()),
        hsv,
    )
    hsv[0] = (hsv[0] + 36f) % 360f
    hsv[1] = (hsv[1] * 0.95f).coerceIn(0.40f, 0.82f)
    hsv[2] = (hsv[2] * 0.95f).coerceIn(0.44f, 0.76f)
    return Color(AndroidColor.HSVToColor(hsv))
}

private fun derivedTertiary(primary: Color): Color {
    if (primary.alpha <= 0f) return Color.Transparent
    val hsv = FloatArray(3)
    AndroidColor.colorToHSV(
        AndroidColor.rgb((primary.red * 255).toInt(), (primary.green * 255).toInt(), (primary.blue * 255).toInt()),
        hsv,
    )
    hsv[0] = (hsv[0] + 324f) % 360f
    hsv[1] = (hsv[1] * 0.88f).coerceIn(0.36f, 0.76f)
    hsv[2] = (hsv[2] * 0.90f).coerceIn(0.40f, 0.70f)
    return Color(AndroidColor.HSVToColor(hsv))
}

private fun hueDistance(a: Int, b: Int, size: Int): Int {
    val direct = abs(a - b)
    return minOf(direct, size - direct)
}
