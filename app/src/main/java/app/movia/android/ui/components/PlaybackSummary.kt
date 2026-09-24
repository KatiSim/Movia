package app.movia.android.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.movia.android.domain.playback.PlaybackReadiness
import app.movia.android.domain.playback.PlaybackSummaryModel
import app.movia.android.ui.theme.MoviaAccent
import app.movia.android.ui.theme.MoviaBorderSubtle
import app.movia.android.ui.theme.MoviaError
import app.movia.android.ui.theme.MoviaRadius
import app.movia.android.ui.theme.MoviaSuccess
import app.movia.android.ui.theme.MoviaTextSecondary
import app.movia.android.ui.theme.MoviaWarning

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PlaybackSummary(
    summary: PlaybackSummaryModel,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(MoviaRadius.medium),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MoviaBorderSubtle),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 11.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                PlaybackFact(summary.quality)
                PlaybackFact(summary.audioSummary)
                if (summary.subtitlesAvailable) PlaybackFact("Субтитры")
            }
            ReadinessBadge(
                readiness = summary.readiness,
                estimatedStartupSeconds = summary.estimatedStartupSeconds,
            )
        }
    }
}

@Composable
private fun PlaybackFact(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MoviaTextSecondary,
        fontWeight = FontWeight.Medium,
    )
}

@Composable
fun ReadinessBadge(
    readiness: PlaybackReadiness,
    estimatedStartupSeconds: Int?,
    modifier: Modifier = Modifier,
) {
    val presentation = readinessPresentation(readiness, estimatedStartupSeconds)
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Icon(
            imageVector = presentation.icon,
            contentDescription = null,
            tint = presentation.color,
        )
        Text(
            text = presentation.label,
            style = MaterialTheme.typography.labelMedium,
            color = presentation.color,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

private data class ReadinessPresentation(
    val label: String,
    val color: androidx.compose.ui.graphics.Color,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
)

private fun readinessPresentation(
    readiness: PlaybackReadiness,
    estimatedStartupSeconds: Int?,
): ReadinessPresentation {
    val startup = estimatedStartupSeconds?.let { " · запуск ~${it} с" }.orEmpty()
    return when (readiness) {
        PlaybackReadiness.READY -> ReadinessPresentation(
            label = "Готово$startup",
            color = MoviaSuccess,
            icon = Icons.Outlined.CheckCircle,
        )
        PlaybackReadiness.HIGH -> ReadinessPresentation(
            label = "Высокая готовность$startup",
            color = MoviaSuccess,
            icon = Icons.Outlined.CheckCircle,
        )
        PlaybackReadiness.PREPARING -> ReadinessPresentation(
            label = "Подготавливаем…",
            color = MoviaAccent,
            icon = Icons.Outlined.Schedule,
        )
        PlaybackReadiness.SLOW -> ReadinessPresentation(
            label = "Источник работает медленно",
            color = MoviaWarning,
            icon = Icons.Outlined.WarningAmber,
        )
        PlaybackReadiness.MAY_WAIT -> ReadinessPresentation(
            label = "Может потребоваться ожидание$startup",
            color = MoviaWarning,
            icon = Icons.Outlined.Schedule,
        )
        PlaybackReadiness.UNAVAILABLE -> ReadinessPresentation(
            label = "Источник сейчас недоступен",
            color = MoviaError,
            icon = Icons.Outlined.WarningAmber,
        )
    }
}
