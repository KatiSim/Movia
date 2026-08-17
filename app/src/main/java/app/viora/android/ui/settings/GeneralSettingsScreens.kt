package app.viora.android.ui.settings

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
import app.viora.android.data.preferences.AppPreferences
import app.viora.android.data.preferences.PlaybackPreferences

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
    SettingsPage(title = "Внешний вид", onBack = onBack, modifier = modifier) {
        item {
            ChoiceSection(
                title = "Тема",
                options = listOf("DARK", "LIGHT", "SYSTEM"),
                selected = preferences.themeMode,
                onSelected = onThemeModeChanged,
                optionLabel = { option ->
                    when (option) {
                        "DARK" -> "Тёмная"
                        "LIGHT" -> "Светлая"
                        "SYSTEM" -> "Системная"
                        else -> option
                    }
                },
            )
        }
        item {
            Text(
                text = "Системная тема следует настройке Android.",
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
                body = "Недоступны в локальной версии. Они появятся после подключения сервера уведомлений — Viora не показывает неработающий переключатель.",
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
                body = "Пока недоступна. Данные Viora сохраняются только на этом устройстве.",
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
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                HelpItem("Как продолжить просмотр?", "На Главной нажмите «Продолжить». Позиция сохраняется автоматически.")
                HelpItem("Как вернуть общую озвучку?", "На странице контента откройте «Параметры воспроизведения» и нажмите «Использовать настройки по умолчанию».")
                HelpItem("Где находится офлайн-контент?", "В разделе «Моё → Скачанное». Файлы хранятся во внутреннем каталоге приложения.")
                HelpItem("Почему контент демонстрационный?", "Текущая сборка проверяет продуктовую архитектуру и работает только с легальным публичным тестовым видео.")
                Spacer(Modifier.padding(bottom = 8.dp))
                Text("Локальная демо-сборка Viora", color = MaterialTheme.colorScheme.onSurfaceVariant)
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
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun manufacturerName(): String = Build.MANUFACTURER.replaceFirstChar { char ->
    if (char.isLowerCase()) char.titlecase() else char.toString()
}
