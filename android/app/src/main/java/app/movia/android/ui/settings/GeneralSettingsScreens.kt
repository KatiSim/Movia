package app.movia.android.ui.settings

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.movia.android.data.preferences.AppPreferences
import app.movia.android.data.preferences.PlaybackPreferences

@Composable
fun DownloadsSettingsScreen(
    preferences: PlaybackPreferences,
    downloadedCount: Int,
    onBack: () -> Unit,
    onWifiOnlyChanged: (Boolean) -> Unit,
    onDeleteAll: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler(onBack = onBack)
    SettingsPage(title = "Загрузки", onBack = onBack, modifier = modifier) {
        item {
            SettingsSwitch(
                title = "Только по Wi‑Fi",
                subtitle = "Не начинать новые загрузки через лимитное соединение",
                checked = preferences.wifiOnlyDownloads,
                onCheckedChange = onWifiOnlyChanged,
            )
        }
        item {
            SettingsInfoCard(
                title = "Офлайн-контент",
                body = if (downloadedCount == 0) "Скачанных элементов нет" else "Скачано элементов: $downloadedCount",
            ) {
                if (downloadedCount > 0) {
                    TextButton(onClick = onDeleteAll) { Text("Удалить все загрузки") }
                }
            }
        }
    }
}

@Composable
fun AppearanceSettingsScreen(
    preferences: AppPreferences,
    onBack: () -> Unit,
    onThemeModeChanged: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler(onBack = onBack)
    @Suppress("UNUSED_VARIABLE")
    val preserveThemePreferenceContract = preferences.themeMode
    SettingsPage(title = "Внешний вид", onBack = onBack, modifier = modifier) {
        item {
            ChoiceSection(
                title = "Тема",
                options = listOf("DARK"),
                selected = "DARK",
                onSelected = { onThemeModeChanged("DARK") },
                optionLabel = { "Тёмная" },
            )
        }
        item {
            Text(
                text = "Cinematic Amber — основная тема Movia v1.",
                modifier = Modifier.padding(horizontal = 16.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
fun AccessibilitySettingsScreen(
    preferences: AppPreferences,
    onBack: () -> Unit,
    onHighContrastChanged: (Boolean) -> Unit,
    onPersistentSeekButtonsChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler(onBack = onBack)
    SettingsPage(title = "Доступность", onBack = onBack, modifier = modifier) {
        item {
            SettingsSwitch(
                title = "Повышенный контраст",
                subtitle = "Усиливает контраст текста и поверхностей",
                checked = preferences.highContrast,
                onCheckedChange = onHighContrastChanged,
            )
        }
        item {
            SettingsSwitch(
                title = "Кнопки перемотки ±10 сек",
                subtitle = "Постоянно показывать крупные кнопки перемотки в плеере",
                checked = preferences.persistentSeekButtons,
                onCheckedChange = onPersistentSeekButtonsChanged,
            )
        }
        item {
            SettingsInfoCard(
                title = "Базовые требования",
                body = "Основные touch-targets — не меньше 48 dp. Значимые иконки имеют accessibility-описания, а интерфейс учитывает системное масштабирование текста.",
            )
        }
    }
}

@Composable
fun NotificationsSettingsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler(onBack = onBack)
    SettingsPage(title = "Уведомления", onBack = onBack, modifier = modifier) {
        item {
            SettingsInfoCard(
                title = "Премьеры и новые серии",
                body = "Недоступны в локальной версии. Они появятся после подключения сервера уведомлений — Movia не показывает неработающий переключатель.",
            )
        }
    }
}

@Composable
fun DevicesSettingsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler(onBack = onBack)
    SettingsPage(title = "Устройство", onBack = onBack, modifier = modifier) {
        item {
            SettingsInfoCard(
                title = "Это устройство",
                body = "${manufacturerName()} ${Build.MODEL}\nAndroid ${Build.VERSION.RELEASE}",
            )
        }
        item {
            SettingsInfoCard(
                title = "Облачная синхронизация",
                body = "Пока недоступна. Данные Movia сохраняются только на этом устройстве.",
            )
        }
    }
}

@Composable
fun HelpSettingsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler(onBack = onBack)
    SettingsPage(title = "Помощь", onBack = onBack, modifier = modifier) {
        item {
            Column(
                Modifier.padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                HelpItem("Как продолжить просмотр?", "На Главной нажмите «Продолжить». Позиция сохраняется автоматически.")
                HelpItem("Где изменить воспроизведение?", "Откройте Профиль → Воспроизведение. Там настраиваются качество, язык и субтитры.")
                HelpItem("Где находится офлайн-контент?", "В разделе «Моё → Скачанное». Файлы хранятся во внутреннем каталоге приложения.")
                HelpItem("Почему контент демонстрационный?", "Текущая сборка проверяет продуктовую архитектуру и работает только с легальным публичным тестовым видео.")
                Spacer(Modifier.padding(bottom = 8.dp))
                Text("Локальная демо-сборка Movia", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun SettingsInfoCard(
    title: String,
    body: String,
    action: @Composable () -> Unit = {},
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            action()
        }
    }
}

@Composable
private fun HelpItem(title: String, body: String) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun manufacturerName(): String = Build.MANUFACTURER.replaceFirstChar { char ->
    if (char.isLowerCase()) char.titlecase() else char.toString()
}
