package app.movia.android.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.ui.graphics.lerp
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.movia.android.ui.theme.MoviaBrandAmber

val MoviaSectionHeaderContentGap = 8.dp

/** Authoritative Movia section heading, optionally with a 48dp "Смотреть все" action. */
@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    actionTestTag: String? = null,
    onClick: (() -> Unit)? = null,
) {
    if (onClick == null) {
        Text(
            text = title,
            modifier = modifier.fillMaxWidth(),
            fontSize = 18.sp,
            lineHeight = 24.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        return
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = title,
            modifier = Modifier.weight(1f),
            fontSize = 18.sp,
            lineHeight = 24.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )

        MoviaSectionAction(
            label = "Смотреть все",
            actionTestTag = actionTestTag,
            onClick = onClick,
        )
    }
}
@Composable
fun MoviaSectionAction(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    actionTestTag: String? = null,
) {
    val actionTrigger = rememberMoviaActionTriggerState()
    val glowAlpha = rememberMoviaNeonFeedbackAlpha(actionTrigger, durationMs = 360)
    val animatedOnClick = rememberMoviaAnimatedAction(actionTrigger, 120L, onClick)
    val actionColor = lerp(
        MaterialTheme.colorScheme.onSurfaceVariant,
        MoviaBrandAmber,
        glowAlpha.coerceIn(0f, 1f),
    )

    val glowShape = RoundedCornerShape(14.dp)
    Box(
        modifier = modifier
            .heightIn(min = 48.dp)
            .clickable(onClick = animatedOnClick)
            .then(if (actionTestTag != null) Modifier.testTag(actionTestTag) else Modifier)
            .background(
                color = MoviaBrandAmber.copy(alpha = 0.10f * glowAlpha),
                shape = glowShape,
            )
            .border(
                width = 1.dp,
                color = MoviaBrandAmber.copy(alpha = 0.48f * glowAlpha),
                shape = glowShape,
            )
            .padding(start = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = label,
                color = actionColor,
                fontSize = 14.sp,
                lineHeight = 20.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
            )
            Box(
                modifier = Modifier.size(20.dp),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.ArrowForward,
                    contentDescription = null,
                    tint = actionColor,
                    modifier = Modifier.size(19.dp),
                )
            }
        }
    }
}
