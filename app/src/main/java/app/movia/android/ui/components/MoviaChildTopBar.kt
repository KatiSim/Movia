package app.movia.android.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.movia.android.ui.theme.MoviaBrandAmber

/** Shared child-screen top bar: Back -> title -> optional contextual action. */
@Composable
fun MoviaChildTopBar(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    actionText: String? = null,
    actionTestTag: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MoviaTapIconButton(
            icon = Icons.AutoMirrored.Outlined.ArrowBack,
            contentDescription = "Назад",
            onClick = onBack,
            modifier = Modifier.size(48.dp),
            iconModifier = Modifier.size(24.dp),
            tint = MaterialTheme.colorScheme.onBackground,
            actionDelayMs = 500L,
        )

        MoviaPageTitle(
            text = title,
            modifier = Modifier.weight(1f),
        )

        if (actionText != null && onAction != null) {
            TextButton(
                onClick = onAction,
                modifier = if (actionTestTag != null) Modifier.testTag(actionTestTag) else Modifier,
                colors = ButtonDefaults.textButtonColors(contentColor = MoviaBrandAmber),
            ) {
                Text(
                    text = actionText,
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
    }
}
