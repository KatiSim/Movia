package app.viora.android.ui.settings

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
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
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Офлайн-контент", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    text = if (downloadedCount == 0) "Скачанных элементов нет" else "Скачано элементов: $downloadedCount",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
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
            )
        }
        item {
            Text(
                text = "DARK — фирменная тёмная тема Viora; LIGHT — светлая; SYSTEM — следует настройке Android.",
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
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Базовые требования", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text("• Основные touch-targets не меньше 48 dp")
                Text("• Значимые иконки имеют accessibility-описания")
                Text("• Интерфейс использует системное масштабирование текста")
                Text("• Основные действия доступны без скрытых жестов")
            }
        }
    }
}

@Composable
fun NotificationsSettingsScreen(
    preferences: AppPreferences,
    onBack: () -> Unit,
    onEnabledChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler(onBack = onBack)
    SettingsPage(title = "Уведомления", onBack = onBack, modifier = modifier) {
        item {
            SettingsSwitch(
                title = "Разрешить уведомления Viora",
                subtitle = "Глобальный переключатель локальных уведомлений приложения",
                checked = preferences.notificationsEnabled,
                onCheckedChange = onEnabledChanged,
            )
        }
        item {
            Text(
                text = "Премьеры и новые серии не симулируются локально: для них потребуется подключённый каталог/сервер уведомлений.",
                modifier = Modifier.padding(horizontal = 16.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
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
    SettingsPage(title = "Устройства", onBack = onBack, modifier = modifier) {
        item {
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Текущее устройство", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text("${Build.MANUFACTURER} ${Build.MODEL}")
                Text("Android ${Build.VERSION.RELEASE} · API ${Build.VERSION.SDK_INT}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    "Cloud-синхронизация не имитируется: она будет включена только после подключения реального аккаунт/backend-провайдера.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
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
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                HelpItem("Как продолжить просмотр?", "На Главной нажмите «Продолжить». Позиция сохраняется автоматически.")
                HelpItem("Как вернуть общую озвучку?", "На странице контента откройте «Параметры воспроизведения» и нажмите «Использовать настройки по умолчанию».")
                HelpItem("Где находится офлайн-контент?", "В разделе «Моё → Скачанное». Файлы хранятся во внутреннем каталоге приложения.")
                HelpItem("Почему контент демонстрационный?", "Текущая сборка проверяет продуктовую архитектуру и работает только с легальным публичным тестовым видео.")
                Spacer(Modifier.padding(bottom = 16.dp))
                Text("Viora · development build", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
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
