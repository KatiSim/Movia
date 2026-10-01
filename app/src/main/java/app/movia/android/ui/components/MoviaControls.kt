package app.movia.android.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.movia.android.ui.theme.MoviaAccent
import app.movia.android.ui.theme.MoviaAccentPressed
import app.movia.android.ui.theme.MoviaBorderSubtle
import app.movia.android.ui.theme.MoviaBrandAmber
import app.movia.android.ui.theme.MoviaMinimumTouch
import app.movia.android.ui.theme.MoviaOnBrandAmber
import app.movia.android.ui.theme.MoviaDisabledContent
import app.movia.android.ui.theme.MoviaDisabledSurface
import app.movia.android.ui.theme.MoviaRadius
import app.movia.android.ui.theme.MoviaSpacing

/**
 * The single visual language for the main action throughout Movia.
 *
 * Primary play actions are always accent-gold, full-size, and high contrast.
 * Screens may add a secondary/supporting line, but should not invent another
 * primary button treatment.
 */
@Composable
fun MoviaPrimaryActionButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leadingIcon: ImageVector? = Icons.Rounded.PlayArrow,
    leadingIconSize: Dp = 30.8.dp,
    supportingText: String? = null,
    animatePlayback: Boolean = true,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val actionTrigger = rememberMoviaActionTriggerState()
    val motion = rememberMoviaPlaybackActionMotion(actionTrigger)
    val animatedOnClick = rememberMoviaAnimatedAction(actionTrigger, 220L, onClick)

    Button(
        onClick = if (animatePlayback) animatedOnClick else onClick,
        interactionSource = interactionSource,
        modifier = modifier
            .heightIn(min = 56.dp)
            .graphicsLayer {
                scaleX = if (animatePlayback) motion.surfaceScale else 1f
                scaleY = if (animatePlayback) motion.surfaceScale else 1f
            },
        shape = RoundedCornerShape(MoviaRadius.control),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (pressed) MoviaAccentPressed else MoviaAccent,
            contentColor = MoviaOnBrandAmber,
            disabledContainerColor = MoviaDisabledSurface,
            disabledContentColor = MoviaDisabledContent,
        ),
        contentPadding = PaddingValues(
            horizontal = 18.dp,
            vertical = 8.dp,
        ),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            leadingIcon?.let {
                Icon(
                    imageVector = it,
                    contentDescription = null,
                    modifier = Modifier.size(leadingIconSize),
                )
            }
            Column(horizontalAlignment = Alignment.Start) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!supportingText.isNullOrBlank()) {
                    Text(
                        text = supportingText,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Medium,
                        color = MoviaOnBrandAmber.copy(alpha = 0.76f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
fun MoviaSecondaryAction(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        modifier = modifier.heightIn(min = MoviaMinimumTouch),
        shape = RoundedCornerShape(MoviaRadius.medium),
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(1.dp, MoviaBorderSubtle),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(21.dp))
            Text(
                text = label,
                modifier = Modifier.padding(start = 8.dp),
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
fun MoviaTapIconButton(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    iconModifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current,
    enabled: Boolean = true,
    actionDelayMs: Long = 0L,
) {
    val actionTrigger = rememberMoviaActionTriggerState()
    val tapAlpha = rememberMoviaIconTapAlpha(actionTrigger)
    val animatedOnClick = rememberMoviaAnimatedAction(actionTrigger, actionDelayMs, onClick)
    IconButton(
        onClick = animatedOnClick,
        enabled = enabled,
        modifier = modifier,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = lerp(tint, MoviaBrandAmber, tapAlpha.coerceIn(0f, 1f)),
            modifier = iconModifier,
        )
    }
}

@Composable
fun MoviaIconAction(
    icon: ImageVector,
    contentDescription: String,
    selected: Boolean = false,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val actionTrigger = rememberMoviaActionTriggerState()
    val tapAlpha = rememberMoviaIconTapAlpha(actionTrigger)
    val animatedOnClick = rememberMoviaAnimatedAction(actionTrigger, 0L, onClick)
    Surface(
        onClick = animatedOnClick,
        modifier = modifier.size(MoviaMinimumTouch),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surface,
        contentColor = if (selected) MoviaAccent else {
            lerp(
                MaterialTheme.colorScheme.onSurface,
                MoviaBrandAmber,
                tapAlpha.coerceIn(0f, 1f),
            )
        },
        border = BorderStroke(1.dp, MoviaBorderSubtle),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                modifier = Modifier.size(23.dp),
            )
        }
    }
}

@Composable
fun MoviaStateMessage(
    title: String,
    description: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = MoviaSpacing.screen, vertical = MoviaSpacing.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(MoviaSpacing.sm),
    ) {
        icon?.let {
            Icon(
                imageVector = it,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(42.dp),
            )
        }
        Text(
            text = title,
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
        )
        Text(
            text = description,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
        )
        if (!actionLabel.isNullOrBlank() && onAction != null) {
            MoviaPrimaryActionButton(
                label = actionLabel,
                onClick = onAction,
                leadingIcon = null,
                animatePlayback = false,
            )
        }
    }
}


/** Exact visual source of truth for the Home Spotlight / My Hero primary action. */
@Composable
fun MoviaSpotlightActionButton(
    label: String,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val largeFont = androidx.compose.ui.platform.LocalDensity.current.fontScale > 1.2f
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val actionTrigger = rememberMoviaActionTriggerState()
    val motion = rememberMoviaPlaybackActionMotion(actionTrigger)
    val animatedOnClick = rememberMoviaAnimatedAction(actionTrigger, 220L, onClick)
    val shape = RoundedCornerShape(MoviaRadius.control)

    Surface(
        onClick = animatedOnClick,
        interactionSource = interactionSource,
        modifier = modifier
            .then(if (largeFont) Modifier.fillMaxWidth() else Modifier.size(width = 203.28.dp, height = 58.08.dp))
            .height(58.08.dp)
            .graphicsLayer {
                scaleX = motion.surfaceScale
                scaleY = motion.surfaceScale
            }
            .semantics(mergeDescendants = true) {
                role = Role.Button
                this.contentDescription = contentDescription
            },
        shape = shape,
        color = androidx.compose.ui.graphics.Color.Transparent,
        contentColor = MoviaOnBrandAmber,
        shadowElevation = 0.dp,
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.82.dp)
                    .background(
                        color = if (pressed) MoviaAccentPressed else MoviaAccent,
                        shape = shape,
                    )
                    .padding(horizontal = if (largeFont) 12.dp else 21.78.dp),
                horizontalArrangement = Arrangement.spacedBy(if (largeFont) 8.dp else 12.1.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Rounded.PlayArrow,
                    contentDescription = null,
                    tint = MoviaOnBrandAmber,
                    modifier = Modifier.size(37.268.dp),
                )
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelLarge,
                    color = MoviaOnBrandAmber,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
