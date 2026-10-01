package app.movia.android.ui.details

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.movia.android.domain.model.MediaContent
import app.movia.android.ui.components.rememberMoviaMediaCoverFeedback
import app.movia.android.ui.components.MoviaMediaCoverFrame
import app.movia.android.ui.components.MediaArtworkPlaceholderStyle
import app.movia.android.ui.components.MediaMetadataRow
import app.movia.android.ui.components.MoviaArtwork
import app.movia.android.ui.components.normalizeMoviaArtworkUrl
import app.movia.android.ui.navigation.MoviaPersonCredit
import app.movia.android.ui.theme.MoviaBackgroundPrimary
import app.movia.android.ui.theme.MoviaBorderSubtle
import app.movia.android.ui.theme.MoviaBrandAmber
import app.movia.android.ui.theme.MoviaSurfaceElevated
import app.movia.android.ui.theme.MoviaTextPrimary
import app.movia.android.ui.theme.MoviaTextSecondary
import coil3.compose.AsyncImage

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PersonFilmographyScreen(
    name: String,
    photoUrl: String?,
    credit: MoviaPersonCredit,
    onBack: () -> Unit,
    onOpenDetails: (String, String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewModel: PersonFilmographyViewModel = viewModel()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(name, photoUrl, credit) {
        viewModel.load(name, photoUrl, credit)
    }
    BackHandler(onBack = onBack)

    val screenWidth = LocalConfiguration.current.screenWidthDp.dp
    val posterWidth = ((screenWidth - 56.dp).coerceAtLeast(0.dp)) * 0.38f
    val resolvedPhoto = uiState.photoUrl ?: photoUrl
    val roleLabel = if (credit == MoviaPersonCredit.DIRECTOR) "Режиссёр" else "Актёр"

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MoviaBackgroundPrimary),
    ) {
        TopAppBar(
            title = {
                Text(
                    text = name,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontWeight = FontWeight.SemiBold,
                )
            },
            navigationIcon = {
                Surface(
                    onClick = onBack,
                    modifier = Modifier
                        .padding(start = 12.dp)
                        .size(48.dp),
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = "Назад",
                            modifier = Modifier.size(24.dp),
                        )
                    }
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MoviaBackgroundPrimary,
                titleContentColor = MoviaTextPrimary,
                navigationIconContentColor = MoviaTextPrimary,
            ),
            windowInsets = WindowInsets.statusBars,
        )
        HorizontalDivider(color = MoviaBorderSubtle)

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = 16.dp,
                top = 20.dp,
                end = 16.dp,
                bottom = 24.dp,
            ),
        ) {
            item(key = "person-header") {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    PersonPortrait(
                        name = name,
                        photoUrl = resolvedPhoto,
                        size = 96.dp,
                    )
                    Text(
                        text = name,
                        color = MoviaTextPrimary,
                        fontSize = 22.sp,
                        lineHeight = 28.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                    )
                    Text(
                        text = roleLabel,
                        color = MoviaTextSecondary,
                        fontSize = 14.sp,
                        lineHeight = 20.sp,
                    )
                }
            }

            item(key = "filmography-title") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Bottom,
                ) {
                    Text(
                        text = "Фильмография",
                        color = MoviaTextPrimary,
                        fontSize = 18.sp,
                        lineHeight = 24.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    if (uiState.items.isNotEmpty()) {
                        Text(
                            text = "От новых к старым",
                            color = MoviaTextSecondary,
                            fontSize = 12.sp,
                            lineHeight = 16.sp,
                        )
                    }
                }
            }

            if (uiState.isLoading && uiState.items.isEmpty()) {
                item(key = "filmography-loading") {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 32.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 2.dp)
                    }
                }
            } else if (uiState.items.isEmpty()) {
                item(key = "filmography-empty") {
                    Text(
                        text = uiState.errorMessage ?: "Фильмография пока недоступна",
                        color = MoviaTextSecondary,
                        fontSize = 14.sp,
                        lineHeight = 20.sp,
                    )
                }
            } else {
                items(uiState.items, key = { "person-media-${it.id}" }) { item ->
                    FilmographyRow(
                        item = item,
                        posterWidth = posterWidth,
                        onClick = { onOpenDetails(item.id, item.title) },
                    )
                }
            }
        }
    }
}

@Composable
private fun PersonPortrait(
    name: String,
    photoUrl: String?,
    size: androidx.compose.ui.unit.Dp,
) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(MoviaSurfaceElevated)
            .border(1.dp, MoviaBorderSubtle, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = name.trim().firstOrNull()?.uppercase() ?: "?",
            color = MoviaBrandAmber,
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
        )
        normalizeMoviaArtworkUrl(photoUrl)?.let { imageUrl ->
            AsyncImage(
                model = imageUrl,
                contentDescription = name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
private fun FilmographyRow(
    item: MediaContent,
    posterWidth: androidx.compose.ui.unit.Dp,
    onClick: () -> Unit,
) {
    val coverFeedback = rememberMoviaMediaCoverFeedback(onClick)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = coverFeedback.onClick),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.Top,
    ) {
        MoviaMediaCoverFrame(
            feedback = coverFeedback,
            modifier = Modifier
                .width(posterWidth)
                .aspectRatio(2f / 3f),
            shape = RoundedCornerShape(14.dp),
        ) {
            MoviaArtwork(
                url = item.posterUrl,
                modifier = Modifier.fillMaxSize(),
                contentDescription = item.title,
                contentScale = ContentScale.Crop,
                placeholderStyle = MediaArtworkPlaceholderStyle.POSTER,
            )
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(top = 4.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = item.title,
                color = MoviaTextPrimary,
                fontSize = 16.sp,
                lineHeight = 22.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            MediaMetadataRow(
                item = item,
                modifier = Modifier.fillMaxWidth(),
                fontSize = 12.sp,
                lineHeight = 16.sp,
            )
        }
    }
}
