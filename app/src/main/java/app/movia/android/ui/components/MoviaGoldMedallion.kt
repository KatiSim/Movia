package app.movia.android.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics

/** Exact Compose rendering of the user-provided 1024×1024 Movia SVG medallions. */
enum class MoviaGoldMedallionKind { Settings, Play, Notification, Profile, Download }

@Composable
fun MoviaGoldMedallion(
    kind: MoviaGoldMedallionKind,
    modifier: Modifier = Modifier,
    description: String? = null,
) {
    Canvas(
        modifier = if (description == null) modifier else modifier.semantics {
            contentDescription = description
        },
    ) {
        val s = size.minDimension / 1024f
        val origin = Offset((size.width - 1024f * s) / 2f, (size.height - 1024f * s) / 2f)
        val gold = svgGoldBrush(30f, 30f, 994f, 994f, s)
        val disc = svgDiscBrush(74f, 74f, 950f, 950f, s)

        withTransform({ translate(origin.x, origin.y) }) {
            drawCircle(gold, 482f * s, Offset(512f * s, 512f * s))
            drawCircle(disc, 438f * s, Offset(512f * s, 512f * s))
            drawCircle(Color(0xFF6F491B), 438f * s, Offset(512f * s, 512f * s), style = Stroke(6f * s))
            drawCircle(Color(0x8CB67D2D), 424f * s, Offset(512f * s, 512f * s), style = Stroke(3f * s))

            when (kind) {
                MoviaGoldMedallionKind.Settings -> drawSettings(s, disc)
                MoviaGoldMedallionKind.Play -> drawPlay(s)
                MoviaGoldMedallionKind.Notification -> drawNotification(s)
                MoviaGoldMedallionKind.Profile -> drawProfile(s)
                MoviaGoldMedallionKind.Download -> drawDownload(s)
            }
        }
    }
}

private fun svgGoldBrush(l:Float,t:Float,r:Float,b:Float,s:Float)=Brush.linearGradient(
    colorStops=arrayOf(0f to Color(0xFFFFF0B8),.18f to Color(0xFFF6CC72),.48f to Color(0xFFC78624),.72f to Color(0xFF8A4F0F),1f to Color(0xFFF3C865)),
    start=Offset(l*s,t*s), end=Offset((l+.9f*(r-l))*s,b*s),
)
private fun svgGoldFaceBrush(l:Float,t:Float,r:Float,b:Float,s:Float)=Brush.linearGradient(
    colorStops=arrayOf(0f to Color(0xFFFFE7A2),.45f to Color(0xFFE0AA46),1f to Color(0xFF9F5B12)),
    start=Offset(l*s,t*s), end=Offset(r*s,b*s),
)
private fun svgDiscBrush(l:Float,t:Float,r:Float,b:Float,s:Float):Brush {
    val w=r-l; val h=b-t
    return Brush.radialGradient(
        colorStops=arrayOf(0f to Color(0xFF2A2824),.58f to Color(0xFF171614),1f to Color(0xFF070707)),
        center=Offset((l+.4f*w)*s,(t+.3f*h)*s), radius=.75f*maxOf(w,h)*s,
    )
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawSettings(s: Float, disc: Brush) {
    val goldFace = svgGoldFaceBrush(216.8f,216.6f,807.2f,807.4f,s)
    val p = Path().apply {
        moveTo(433.3f*s,236.4f*s); quadraticTo(433.3f*s,216.6f*s,472.7f*s,216.6f*s)
        lineTo(551.4f*s,216.6f*s); quadraticTo(590.7f*s,216.6f*s,590.7f*s,236.4f*s); lineTo(590.7f*s,276f*s)
        quadraticTo(590.7f*s,295.8f*s,608f*s,305.8f*s); lineTo(642.6f*s,325.7f*s); quadraticTo(659.9f*s,335.7f*s,677f*s,325.8f*s)
        lineTo(711.4f*s,306f*s); quadraticTo(728.5f*s,296.1f*s,748.2f*s,330.2f*s); lineTo(787.5f*s,398.4f*s)
        quadraticTo(807.2f*s,432.5f*s,790.1f*s,442.4f*s); lineTo(755.8f*s,462.2f*s); quadraticTo(738.6f*s,472.1f*s,738.6f*s,492.1f*s)
        lineTo(738.6f*s,532f*s); quadraticTo(738.6f*s,551.9f*s,755.8f*s,561.8f*s); lineTo(790.1f*s,581.6f*s)
        quadraticTo(807.2f*s,591.5f*s,787.5f*s,625.6f*s); lineTo(748.2f*s,693.8f*s); quadraticTo(728.5f*s,727.9f*s,711.4f*s,718f*s)
        lineTo(677f*s,698.2f*s); quadraticTo(659.9f*s,688.3f*s,642.6f*s,698.3f*s); lineTo(608f*s,718.2f*s)
        quadraticTo(590.7f*s,728.2f*s,590.7f*s,748f*s); lineTo(590.7f*s,787.6f*s); quadraticTo(590.7f*s,807.4f*s,551.4f*s,807.4f*s)
        lineTo(472.7f*s,807.4f*s); quadraticTo(433.3f*s,807.4f*s,433.3f*s,787.6f*s); lineTo(433.3f*s,748f*s)
        quadraticTo(433.3f*s,728.2f*s,416f*s,718.2f*s); lineTo(381.4f*s,698.3f*s); quadraticTo(364.1f*s,688.3f*s,347f*s,698.2f*s)
        lineTo(312.6f*s,718f*s); quadraticTo(295.5f*s,727.9f*s,275.8f*s,693.8f*s); lineTo(236.5f*s,625.6f*s)
        quadraticTo(216.8f*s,591.5f*s,233.9f*s,581.6f*s); lineTo(268.2f*s,561.8f*s); quadraticTo(285.4f*s,551.9f*s,285.4f*s,532f*s)
        lineTo(285.4f*s,492.1f*s); quadraticTo(285.4f*s,472.1f*s,268.2f*s,462.2f*s); lineTo(233.9f*s,442.4f*s)
        quadraticTo(216.8f*s,432.5f*s,236.5f*s,398.4f*s); lineTo(275.8f*s,330.2f*s); quadraticTo(295.5f*s,296.1f*s,312.6f*s,306f*s)
        lineTo(347f*s,325.8f*s); quadraticTo(364.1f*s,335.7f*s,381.4f*s,325.7f*s); lineTo(416f*s,305.8f*s)
        quadraticTo(433.3f*s,295.8f*s,433.3f*s,276f*s); close()
    }
    drawPath(p, goldFace); drawPath(p, goldFace, style = Stroke(14f*s, join = StrokeJoin.Round)); drawCircle(disc,122f*s,Offset(512f*s,512f*s))
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawPlay(s: Float) {
    val goldFace = svgGoldFaceBrush(392f,333.472f,701.047f,690.528f,s)
    withTransform({ translate(512f*s,512f*s); scale(1.3225f,1.3225f); translate(-512f*s,-512f*s) }) {
        val raw = PathParser().parsePathString(
            "M392 362.912 L392 661.088 A34 34 0 0 0 443.009 690.528 L701.047 541.439 A34 34 0 0 0 701.047 482.561 L443.009 333.472 A34 34 0 0 0 392 362.912 Z",
        ).toPath().apply { transform(Matrix().apply { scale(s, s) }) }
        drawPath(raw,goldFace); drawPath(raw,Color(0xFFF8D98C),style=Stroke(7f*s))
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawNotification(s: Float) {
    withTransform({ translate(166.4f*s,117.8f*s); scale(.675f,.675f, pivot = Offset.Zero) }) {
        val capFace=svgGoldFaceBrush(478f,220f,546f,275f,s)
        val cap=Path().apply { moveTo(478f*s,245f*s); quadraticTo(478f*s,220f*s,503f*s,220f*s); lineTo(521f*s,220f*s); quadraticTo(546f*s,220f*s,546f*s,245f*s); lineTo(546f*s,275f*s); lineTo(478f*s,275f*s); close() }
        drawPath(cap,capFace); drawPath(cap,Color(0xFFF0C46E),style=Stroke(7f*s,join=StrokeJoin.Round))
        val bellFace=svgGoldFaceBrush(194f,266f,830f,836f,s)
        val bell=Path().apply { moveTo(512f*s,266f*s); cubicTo(414f*s,266f*s,350f*s,323f*s,337f*s,421f*s); cubicTo(329f*s,478f*s,332f*s,552f*s,323f*s,608f*s); cubicTo(314f*s,663f*s,292f*s,704f*s,252f*s,744f*s); lineTo(208f*s,788f*s); quadraticTo(194f*s,802f*s,200f*s,819f*s); quadraticTo(207f*s,836f*s,229f*s,836f*s); lineTo(795f*s,836f*s); quadraticTo(817f*s,836f*s,824f*s,819f*s); quadraticTo(830f*s,802f*s,816f*s,788f*s); lineTo(772f*s,744f*s); cubicTo(732f*s,704f*s,710f*s,663f*s,701f*s,608f*s); cubicTo(692f*s,552f*s,695f*s,478f*s,687f*s,421f*s); cubicTo(674f*s,323f*s,610f*s,266f*s,512f*s,266f*s); close() }
        drawPath(bell,bellFace); drawPath(bell,Color(0xFFF0C46E),style=Stroke(7f*s,join=StrokeJoin.Round))
        val clapFace=svgGoldFaceBrush(442f,866f,582f,948f,s)
        val clap=Path().apply { moveTo(442f*s,866f*s); cubicTo(448f*s,920f*s,474f*s,948f*s,512f*s,948f*s); cubicTo(550f*s,948f*s,576f*s,920f*s,582f*s,866f*s); close() }
        drawPath(clap,clapFace); drawPath(clap,Color(0xFFF1C267),style=Stroke(7f*s,join=StrokeJoin.Round))
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawProfile(s: Float) {
    val headFace=svgGoldFaceBrush(400f,226f,624f,450f,s)
    val bodyFace=svgGoldFaceBrush(270f,500f,754f,774f,s)
    drawCircle(headFace,112f*s,Offset(512f*s,338f*s)); drawCircle(Color(0xFFF4CE7A),112f*s,Offset(512f*s,338f*s),style=Stroke(7f*s))
    val p=Path().apply { moveTo(270f*s,744f*s); cubicTo(270f*s,600f*s,378f*s,500f*s,512f*s,500f*s); cubicTo(646f*s,500f*s,754f*s,600f*s,754f*s,744f*s); quadraticTo(754f*s,774f*s,724f*s,774f*s); lineTo(300f*s,774f*s); quadraticTo(270f*s,774f*s,270f*s,744f*s); close() }
    drawPath(p,bodyFace); drawPath(p,Color(0xFFF3CA72),style=Stroke(7f*s,join=StrokeJoin.Round))
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawDownload(s: Float) {
    val arrowFace=svgGoldFaceBrush(333f,170f,691f,649f,s)
    val baseFace=svgGoldFaceBrush(282f,712f,742f,776f,s)
    val p=Path().apply { moveTo(458f*s,194f*s); quadraticTo(458f*s,170f*s,482f*s,170f*s); lineTo(542f*s,170f*s); quadraticTo(566f*s,170f*s,566f*s,194f*s); lineTo(566f*s,446f*s); lineTo(650f*s,446f*s); quadraticTo(671f*s,446f*s,681f*s,463f*s); quadraticTo(691f*s,481f*s,676f*s,497f*s); lineTo(538f*s,636f*s); quadraticTo(526f*s,649f*s,512f*s,649f*s); quadraticTo(498f*s,649f*s,486f*s,636f*s); lineTo(348f*s,497f*s); quadraticTo(333f*s,481f*s,343f*s,463f*s); quadraticTo(353f*s,446f*s,374f*s,446f*s); lineTo(458f*s,446f*s); close() }
    drawPath(p,arrowFace); drawPath(p,Color(0xFFF4CF7B),style=Stroke(7f*s,join=StrokeJoin.Round))
    val left=282f*s; val top=712f*s; val right=742f*s; val bottom=776f*s; val r=32f*s
    val base=Path().apply { moveTo(left+r,top); lineTo(right-r,top); quadraticTo(right,top,right,top+r); quadraticTo(right,bottom,right-r,bottom); lineTo(left+r,bottom); quadraticTo(left,bottom,left,bottom-r); quadraticTo(left,top,left+r,top); close() }
    drawPath(base,baseFace); drawPath(base,Color(0xFFF4CF7B),style=Stroke(7f*s))
}

@Composable
fun MoviaGoldPlayMedallion(modifier: Modifier = Modifier) {
    MoviaGoldMedallion(MoviaGoldMedallionKind.Play, modifier = modifier)
}
