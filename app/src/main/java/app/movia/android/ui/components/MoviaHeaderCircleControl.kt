package app.movia.android.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.movia.android.ui.theme.MoviaBorderMedium
import app.movia.android.ui.theme.MoviaSurfaceElevated
import app.movia.android.ui.theme.MoviaTextPrimary

enum class MoviaHeaderGlyph { Profile, Download, Settings, Notification }

/** Header visual shell matches the Home Favorite control exactly. */
@Composable
fun MoviaHeaderCircleControl(
    glyph: MoviaHeaderGlyph,
    modifier: Modifier = Modifier,
    glyphModifier: Modifier = Modifier,
    description: String? = null,
    glyphColor: Color = MoviaTextPrimary,
) {
    val visualSize = if (glyph == MoviaHeaderGlyph.Profile) 69.dp else 41.4.dp
    val glyphSize = if (glyph == MoviaHeaderGlyph.Profile) 39.93.dp else 23.958.dp

    Surface(
        modifier = modifier.size(visualSize),
        shape = CircleShape,
        color = MoviaSurfaceElevated,
        border = BorderStroke(1.dp, MoviaBorderMedium),
    ) {
        Box(contentAlignment = Alignment.Center) {
            MoviaHeaderGlyphIcon(
                glyph = glyph,
                color = glyphColor,
                modifier = Modifier
                    .size(glyphSize)
                    .then(glyphModifier),
                description = description,
            )
        }
    }
}
@Composable
fun MoviaHeaderGlyphIcon(
    glyph: MoviaHeaderGlyph,
    color: Color,
    modifier: Modifier = Modifier,
    description: String? = null,
) {
    Canvas(
        modifier = modifier.then(
            if (description == null) Modifier else Modifier.semantics { contentDescription = description },
        ),
    ) {
        when (glyph) {
            MoviaHeaderGlyph.Profile -> drawProfileGlyph(color)
            MoviaHeaderGlyph.Download -> drawDownloadGlyph(color)
            MoviaHeaderGlyph.Settings -> drawSettingsGlyph(color)
            MoviaHeaderGlyph.Notification -> drawNotificationGlyph(color)
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawProfileGlyph(color: Color) {
    // Preserve supplied SVG profile geometry; normalize its visual bounds (270..754, 226..774) into glyph box.
    val scale = size.minDimension / 548f
    withTransform({ translate((size.width - 484f * scale) / 2f - 270f * scale, -226f * scale) }) {
        drawCircle(color, 112f * scale, Offset(512f * scale, 338f * scale))
        val p = Path().apply {
            moveTo(270f*scale,744f*scale); cubicTo(270f*scale,600f*scale,378f*scale,500f*scale,512f*scale,500f*scale)
            cubicTo(646f*scale,500f*scale,754f*scale,600f*scale,754f*scale,744f*scale); quadraticTo(754f*scale,774f*scale,724f*scale,774f*scale)
            lineTo(300f*scale,774f*scale); quadraticTo(270f*scale,774f*scale,270f*scale,744f*scale); close()
        }
        drawPath(p, color)
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawDownloadGlyph(color: Color) {
    // Preserve supplied SVG download arrow/base geometry; normalize combined bounds 282..742, 170..776.
    val scale = size.minDimension / 606f
    withTransform({ translate((size.width - 460f * scale) / 2f - 282f * scale, -170f * scale) }) {
        val p=Path().apply {
            moveTo(458f*scale,194f*scale); quadraticTo(458f*scale,170f*scale,482f*scale,170f*scale); lineTo(542f*scale,170f*scale)
            quadraticTo(566f*scale,170f*scale,566f*scale,194f*scale); lineTo(566f*scale,446f*scale); lineTo(650f*scale,446f*scale)
            quadraticTo(671f*scale,446f*scale,681f*scale,463f*scale); quadraticTo(691f*scale,481f*scale,676f*scale,497f*scale)
            lineTo(538f*scale,636f*scale); quadraticTo(526f*scale,649f*scale,512f*scale,649f*scale); quadraticTo(498f*scale,649f*scale,486f*scale,636f*scale)
            lineTo(348f*scale,497f*scale); quadraticTo(333f*scale,481f*scale,343f*scale,463f*scale); quadraticTo(353f*scale,446f*scale,374f*scale,446f*scale)
            lineTo(458f*scale,446f*scale); close()
        }
        drawPath(p,color)
        val l=282f*scale; val t=712f*scale; val r=742f*scale; val b=776f*scale; val rr=32f*scale
        val base=Path().apply { moveTo(l+rr,t); lineTo(r-rr,t); quadraticTo(r,t,r,t+rr); quadraticTo(r,b,r-rr,b); lineTo(l+rr,b); quadraticTo(l,b,l,b-rr); quadraticTo(l,t,l+rr,t); close() }
        drawPath(base,color)
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawSettingsGlyph(color: Color) {
    // Exact supplied gear path, normalized from its path bounds into the glyph box.
    val raw = PathParser().parsePathString("M433.3 236.4 Q433.3 216.6 472.7 216.6 L551.4 216.6 Q590.7 216.6 590.7 236.4 L590.7 276 Q590.7 295.8 608 305.8 L642.6 325.7 Q659.9 335.7 677 325.8 L711.4 306 Q728.5 296.1 748.2 330.2 L787.5 398.4 Q807.2 432.5 790.1 442.4 L755.8 462.2 Q738.6 472.1 738.6 492.1 L738.6 532 Q738.6 551.9 755.8 561.8 L790.1 581.6 Q807.2 591.5 787.5 625.6 L748.2 693.8 Q728.5 727.9 711.4 718 L677 698.2 Q659.9 688.3 642.6 698.3 L608 718.2 Q590.7 728.2 590.7 748 L590.7 787.6 Q590.7 807.4 551.4 807.4 L472.7 807.4 Q433.3 807.4 433.3 787.6 L433.3 748 Q433.3 728.2 416 718.2 L381.4 698.3 Q364.1 688.3 347 698.2 L312.6 718 Q295.5 727.9 275.8 693.8 L236.5 625.6 Q216.8 591.5 233.9 581.6 L268.2 561.8 Q285.4 551.9 285.4 532 L285.4 492.1 Q285.4 472.1 268.2 462.2 L233.9 442.4 Q216.8 432.5 236.5 398.4 L275.8 330.2 Q295.5 296.1 312.6 306 L347 325.8 Q364.1 335.7 381.4 325.7 L416 305.8 Q433.3 295.8 433.3 276 Z").toPath()
    val scale = size.minDimension / 590.8f
    raw.transform(Matrix().apply { scale(scale,scale); translate(-216.8f,-216.6f) })
    drawPath(raw,color)
    drawCircle(Color.Transparent,122f*scale,Offset((512f-216.8f)*scale,(512f-216.6f)*scale))
    drawCircle(MoviaSurfaceElevated,122f*scale,Offset((512f-216.8f)*scale,(512f-216.6f)*scale))
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawNotificationGlyph(color: Color) {
    // Preserve cap/body/clapper paths. Combined source bounds are x=194..830, y=220..948.
    val scale = size.minDimension / 728f
    withTransform({ translate((size.width - 636f*scale)/2f - 194f*scale, -220f*scale) }) {
        val cap=Path().apply { moveTo(478f*scale,245f*scale); quadraticTo(478f*scale,220f*scale,503f*scale,220f*scale); lineTo(521f*scale,220f*scale); quadraticTo(546f*scale,220f*scale,546f*scale,245f*scale); lineTo(546f*scale,275f*scale); lineTo(478f*scale,275f*scale); close() }
        drawPath(cap,color)
        val bell=Path().apply { moveTo(512f*scale,266f*scale); cubicTo(414f*scale,266f*scale,350f*scale,323f*scale,337f*scale,421f*scale); cubicTo(329f*scale,478f*scale,332f*scale,552f*scale,323f*scale,608f*scale); cubicTo(314f*scale,663f*scale,292f*scale,704f*scale,252f*scale,744f*scale); lineTo(208f*scale,788f*scale); quadraticTo(194f*scale,802f*scale,200f*scale,819f*scale); quadraticTo(207f*scale,836f*scale,229f*scale,836f*scale); lineTo(795f*scale,836f*scale); quadraticTo(817f*scale,836f*scale,824f*scale,819f*scale); quadraticTo(830f*scale,802f*scale,816f*scale,788f*scale); lineTo(772f*scale,744f*scale); cubicTo(732f*scale,704f*scale,710f*scale,663f*scale,701f*scale,608f*scale); cubicTo(692f*scale,552f*scale,695f*scale,478f*scale,687f*scale,421f*scale); cubicTo(674f*scale,323f*scale,610f*scale,266f*scale,512f*scale,266f*scale); close() }
        drawPath(bell,color)
        val clap=Path().apply { moveTo(442f*scale,866f*scale); cubicTo(448f*scale,920f*scale,474f*scale,948f*scale,512f*scale,948f*scale); cubicTo(550f*scale,948f*scale,576f*scale,920f*scale,582f*scale,866f*scale); close() }
        drawPath(clap,color)
    }
}
