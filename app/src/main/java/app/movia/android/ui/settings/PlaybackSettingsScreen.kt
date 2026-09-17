package app.movia.android.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.movia.android.data.preferences.PlaybackPreferences
import app.movia.android.ui.theme.MoviaBrandAmber
import app.movia.android.ui.theme.MoviaOnBrandAmber
import app.movia.android.ui.theme.MoviaBorderSubtle

private val audioOptions = listOf("Auto", "LostFilm", "HDRezka", "Original")
private val qualityOptions = listOf("Auto", "1080p", "720p", "480p")
@Composable
fun PlaybackSettingsScreen(
    preferences: PlaybackPreferences,
    onBack: () -> Unit,
    onAudioSelected: (String) -> Unit,
    onQualitySelected: (String) -> Unit,
    onSubtitlesChanged: (Boolean) -> Unit,
    onAutoNextChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler(onBack = onBack)
    SettingsPage(title = "Воспроизведение", onBack = onBack, modifier = modifier) {
        item { SettingsSectionLabel("По умолчанию") }
        item {
            ChoiceSection(
                title = "Озвучка",
                options = audioOptions,
                selected = preferences.audio,
                onSelected = onAudioSelected,
            )
        }
        item {
            ChoiceSection(
                title = "Качество",
                options = qualityOptions,
                selected = preferences.quality,
                onSelected = onQualitySelected,
            )
        }
        item { SettingsSectionLabel("Поведение") }
        item {
            SettingsSwitch(
                title = "Субтитры",
                subtitle = "Включать по умолчанию, когда они доступны",
                checked = preferences.subtitlesEnabled,
                onCheckedChange = onSubtitlesChanged,
            )
        }
        item {
            SettingsSwitch(
                title = "Следующая серия",
                subtitle = "Автоматически готовить следующий эпизод",
                checked = preferences.autoNextEnabled,
                onCheckedChange = onAutoNextChanged,
            )
        }
        item { Spacer(Modifier.padding(bottom = 8.dp)) }
    }
}

@Composable
internal fun ChoiceSection(
    title: String,
    options: List<String>,
    selected: String,
    onSelected: (String) -> Unit,
    optionLabel: (String) -> String = { it },
) {
    Column(
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            title,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .height(36.dp),
            shape = RoundedCornerShape(10.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
        ) {
            Row(
                modifier = Modifier.padding(2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                options.forEach { option ->
                    val isSelected = option == selected
                    Surface(
                        onClick = { onSelected(option) },
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                        shape = RoundedCornerShape(8.dp),
                        color = if (isSelected) MoviaBrandAmber else Color.Transparent,
                        contentColor = if (isSelected) {
                            MoviaOnBrandAmber
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                text = optionLabel(option),
                                fontSize = 13.sp,
                                lineHeight = 16.sp,
                                fontWeight = if (isSelected) {
                                    FontWeight.SemiBold
                                } else {
                                    FontWeight.Medium
                                },
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun SettingsSectionLabel(title: String) {
    Text(
        text = title,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.6.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(horizontal = 16.dp),
    )
}

@Composable
internal fun SettingsSwitch(
    title: String,
    subtitle: String? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (subtitle.isNullOrBlank()) {
            Text(
                title,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        } else {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                uncheckedThumbColor = MaterialTheme.colorScheme.onSurfaceVariant,
                uncheckedTrackColor = MaterialTheme.colorScheme.surfaceVariant,
                uncheckedBorderColor = MoviaBorderSubtle,
                checkedThumbColor = MoviaOnBrandAmber,
                checkedTrackColor = MoviaBrandAmber,
                checkedBorderColor = Color.Transparent,
            ),
        )
    }
}

@Composable
internal fun SettingsDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(start = 16.dp),
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsPage(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit,
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = title,
                        color = MaterialTheme.colorScheme.onBackground,
                        fontSize = 24.sp,
                        lineHeight = 32.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = "Назад",
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    scrolledContainerColor = MaterialTheme.colorScheme.surface,
                    navigationIconContentColor = MaterialTheme.colorScheme.onBackground,
                    titleContentColor = MaterialTheme.colorScheme.onBackground,
                ),
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(top = 16.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            content = content,
        )
    }
}
