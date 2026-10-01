package app.movia.android.ui.notifications

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.movia.android.domain.model.LibraryMediaRecord
import app.movia.android.domain.model.MediaContent
import app.movia.android.ui.components.MoviaTapIconButton
import app.movia.android.ui.components.MediaArtworkPlaceholderStyle
import app.movia.android.ui.components.MoviaArtwork
import app.movia.android.ui.components.rememberMoviaPlaybackActionMotion
import app.movia.android.ui.components.rememberMoviaAnimatedAction
import app.movia.android.ui.components.rememberMoviaActionTriggerState
import app.movia.android.ui.theme.MoviaBackgroundPrimary
import app.movia.android.ui.theme.MoviaBorderSubtle
import app.movia.android.ui.theme.MoviaBrandAmber
import app.movia.android.ui.theme.MoviaOnBrandAmber
import app.movia.android.ui.theme.MoviaSurfaceElevated
import app.movia.android.ui.theme.MoviaTextPrimary
import app.movia.android.ui.theme.MoviaTextSecondary


data class ReleaseNotificationUiItem(
    val key: String,
    val record: LibraryMediaRecord,
    val media: MediaContent? = null,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReleaseNotificationsSheet(
    items: List<ReleaseNotificationUiItem>,
    onDismissRequest: () -> Unit,
    onWatchNow: (ReleaseNotificationUiItem) -> Unit,
    onRemove: (ReleaseNotificationUiItem) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
        containerColor = MoviaBackgroundPrimary,
        contentColor = MoviaTextPrimary,
        dragHandle = null,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 24.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, top = 16.dp, end = 12.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Уведомления",
                        color = MoviaTextPrimary,
                        fontSize = 22.sp,
                        lineHeight = 28.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = if (items.isEmpty()) "Новых релизов нет" else "Доступно сейчас",
                        color = MoviaTextSecondary,
                        fontSize = 14.sp,
                        lineHeight = 20.sp,
                    )
                }
                MoviaTapIconButton(
                    icon = Icons.Outlined.Close,
                    contentDescription = "Закрыть уведомления",
                    onClick = onDismissRequest,
                    modifier = Modifier.size(48.dp),
                    tint = MoviaTextPrimary,
                    actionDelayMs = 500L,
                )
            }
            HorizontalDivider(color = MoviaBorderSubtle)

            if (items.isEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 36.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = "Пока ничего нового",
                        color = MoviaTextPrimary,
                        fontSize = 18.sp,
                        lineHeight = 24.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = "Когда контент из «Жду выхода» станет доступен для просмотра, он появится здесь.",
                        color = MoviaTextSecondary,
                        fontSize = 14.sp,
                        lineHeight = 20.sp,
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 640.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        start = 16.dp,
                        top = 14.dp,
                        end = 16.dp,
                        bottom = 12.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(items, key = { it.key }) { item ->
                        ReleaseNotificationRow(
                            item = item,
                            onWatchNow = { onWatchNow(item) },
                            onRemove = { onRemove(item) },
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReleaseNotificationRow(
    item: ReleaseNotificationUiItem,
    onWatchNow: () -> Unit,
    onRemove: () -> Unit,
) {
    val watchInteractionSource = remember { MutableInteractionSource() }
    val watchActionTrigger = rememberMoviaActionTriggerState()
    val watchMotion = rememberMoviaPlaybackActionMotion(watchActionTrigger)
    val animatedWatchNow = rememberMoviaAnimatedAction(watchActionTrigger, 220L, onWatchNow)
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { target ->
            if (target == SwipeToDismissBoxValue.EndToStart) {
                onRemove()
                true
            } else {
                target == SwipeToDismissBoxValue.Settled
            }
        },
        positionalThreshold = { totalDistance -> totalDistance * 0.35f },
    )

    SwipeToDismissBox(
        state = dismissState,
        enableDismissFromStartToEnd = false,
        enableDismissFromEndToStart = true,
        backgroundContent = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 164.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(MoviaSurfaceElevated),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Column(
                    modifier = Modifier.padding(end = 22.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(
                        imageVector = Icons.Outlined.DeleteOutline,
                        contentDescription = null,
                        tint = MoviaBrandAmber,
                        modifier = Modifier.size(24.dp),
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Убрать",
                        color = MoviaTextSecondary,
                        fontSize = 12.sp,
                        lineHeight = 16.sp,
                    )
                }
            }
        },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(MoviaSurfaceElevated)
                .padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            MoviaArtwork(
                url = item.media?.posterUrl,
                modifier = Modifier
                    .width(72.dp)
                    .height(108.dp)
                    .clip(RoundedCornerShape(12.dp)),
                contentDescription = item.record.title,
                contentScale = ContentScale.Crop,
                placeholderStyle = MediaArtworkPlaceholderStyle.POSTER,
            )

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = item.record.title,
                    color = MoviaTextPrimary,
                    fontSize = 16.sp,
                    lineHeight = 22.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "Уже можно смотреть",
                    color = MoviaBrandAmber,
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    fontWeight = FontWeight.Medium,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(
                        onClick = animatedWatchNow,
                        interactionSource = watchInteractionSource,
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp)
                            .graphicsLayer {
                                scaleX = watchMotion.surfaceScale
                                scaleY = watchMotion.surfaceScale
                            },
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MoviaBrandAmber,
                            contentColor = MoviaOnBrandAmber,
                        ),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.PlayArrow,
                            contentDescription = null,
                            modifier = Modifier
                                .size(20.dp)
                                .graphicsLayer {
                                    scaleX = 1f
                                    scaleY = 1f
                                },
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Смотреть", maxLines = 1)
                    }
                    OutlinedButton(
                        onClick = onRemove,
                        modifier = Modifier.heightIn(min = 48.dp),
                        shape = RoundedCornerShape(14.dp),
                    ) {
                        Text("Убрать", maxLines = 1)
                    }
                }
            }
        }
    }
}
