package app.movia.android.ui.profile

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.movia.android.data.preferences.AppPreferences
import app.movia.android.data.preferences.PlaybackPreferences
import app.movia.android.ui.settings.ChoiceSection
import app.movia.android.ui.settings.SettingsDivider
import app.movia.android.ui.settings.SettingsPage
import app.movia.android.ui.settings.SettingsSectionLabel
import app.movia.android.ui.settings.SettingsSwitch
import app.movia.android.ui.theme.MoviaBrandAmber
import app.movia.android.ui.theme.MoviaBorderSubtle

private val profileAudioOptions = listOf("Auto", "LostFilm", "HDRezka", "Original")
private val profileQualityOptions = listOf("Auto", "1080p", "720p", "480p")
private val profileThemeOptions = listOf("DARK", "SYSTEM")

@Composable
fun ProfileScreen(
    preferences: AppPreferences,
    playbackPreferences: PlaybackPreferences,
    downloadedCount: Int,
    modifier: Modifier = Modifier,
    onBack: () -> Unit,
    onOpenSettings: (String) -> Unit,
    onAudioSelected: (String) -> Unit,
    onQualitySelected: (String) -> Unit,
    onSubtitlesChanged: (Boolean) -> Unit,
    onAutoNextChanged: (Boolean) -> Unit,
    onPersistentSeekButtonsChanged: (Boolean) -> Unit,
    onWifiOnlyChanged: (Boolean) -> Unit,
    onThemeModeChanged: (String) -> Unit,
    onHighContrastChanged: (Boolean) -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val versionName = remember(context) { installedVersionName(context) }
    val deviceLabel = remember { Build.MODEL.ifBlank { "Android" } }

    BackHandler(onBack = onBack)

    SettingsPage(
        title = "Настройки и профиль",
        onBack = onBack,
        modifier = modifier,
    ) {
        item { LocalProfileCard(deviceLabel = deviceLabel) }

        item { SettingsSectionLabel("ВОСПРОИЗВЕДЕНИЕ") }
        item {
            SettingsPanel {
                ChoiceSection(
                    title = "Озвучка",
                    options = profileAudioOptions,
                    selected = playbackPreferences.audio,
                    onSelected = onAudioSelected,
                )
                SettingsDivider()
                ChoiceSection(
                    title = "Качество видео",
                    options = profileQualityOptions,
                    selected = playbackPreferences.quality,
                    onSelected = onQualitySelected,
                )
                SettingsDivider()
                SettingsSwitch(
                    title = "Автопереход к следующей серии",
                    checked = playbackPreferences.autoNextEnabled,
                    onCheckedChange = onAutoNextChanged,
                )
                SettingsDivider()
                SettingsSwitch(
                    title = "Субтитры по умолчанию",
                    checked = playbackPreferences.subtitlesEnabled,
                    onCheckedChange = onSubtitlesChanged,
                )
                SettingsDivider()
                SettingsSwitch(
                    title = "Кнопки перемотки ±10 сек",
                    checked = preferences.persistentSeekButtons,
                    onCheckedChange = onPersistentSeekButtonsChanged,
                )
            }
        }

        item { SettingsSectionLabel("ЗАГРУЗКИ И ПАМЯТЬ") }
        item {
            SettingsPanel {
                SettingsSwitch(
                    title = "Загружать только по Wi‑Fi",
                    checked = playbackPreferences.wifiOnlyDownloads,
                    onCheckedChange = onWifiOnlyChanged,
                )
                SettingsDivider()
                SettingsLinkCard(
                    title = "Скачанное и память",
                    value = if (downloadedCount == 0) {
                        "Нет файлов"
                    } else {
                        downloadedCount.toString() + " " + downloadCountLabel(downloadedCount)
                    },
                    onClick = { onOpenSettings("downloads") },
                )
            }
        }

        item { SettingsSectionLabel("ИНТЕРФЕЙС") }
        item {
            SettingsPanel {
                ChoiceSection(
                    title = "Тема",
                    options = profileThemeOptions,
                    selected = preferences.themeMode,
                    onSelected = onThemeModeChanged,
                    optionLabel = { if (it == "SYSTEM") "Системная" else "Тёмная" },
                )
                SettingsDivider()
                SettingsSwitch(
                    title = "Повышенный контраст",
                    checked = preferences.highContrast,
                    onCheckedChange = onHighContrastChanged,
                )
            }
        }

        item { SettingsSectionLabel("ПОДДЕРЖКА") }
        item {
            SettingsPanel {
                SettingsLinkCard(
                    title = "Частые вопросы и справка",
                    onClick = { onOpenSettings("help") },
                )
            }
        }

        item {
            Text(
                text = "Movia " + versionName + " • Android " + Build.VERSION.RELEASE,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SettingsPanel(
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MoviaBorderSubtle),
    ) {
        Column(content = { content() })
    }
}

@Composable
private fun SettingsLinkCard(
    title: String,
    value: String? = null,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        color = Color.Transparent,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp)
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = title,
                modifier = Modifier.weight(1f),
                fontWeight = FontWeight.Medium,
            )
            if (!value.isNullOrBlank()) {
                Text(
                    text = value,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                Icons.Outlined.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun LocalProfileCard(deviceLabel: String) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MoviaBorderSubtle),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                Icons.Outlined.Person,
                contentDescription = null,
                modifier = Modifier.size(36.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text("Локальный профиль", fontWeight = FontWeight.SemiBold)
                Text(
                    "Movia • " + deviceLabel,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

private fun downloadCountLabel(count: Int): String {
    val mod100 = count % 100
    val mod10 = count % 10
    return when {
        mod100 in 11..14 -> "загрузок"
        mod10 == 1 -> "загрузка"
        mod10 in 2..4 -> "загрузки"
        else -> "загрузок"
    }
}

@Suppress("DEPRECATION")
private fun installedVersionName(context: Context): String {
    val info = if (Build.VERSION.SDK_INT >= 33) {
        context.packageManager.getPackageInfo(
            context.packageName,
            PackageManager.PackageInfoFlags.of(0),
        )
    } else {
        context.packageManager.getPackageInfo(context.packageName, 0)
    }
    return info.versionName ?: "—"
}
