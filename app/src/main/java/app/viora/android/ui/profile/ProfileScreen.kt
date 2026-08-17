package app.viora.android.ui.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccessibilityNew
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.outlined.NotificationsNone
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.PlayCircleOutline
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

data class ProfileEntry(val title: String, val subtitle: String, val icon: ImageVector, val route: String)

private val profileEntries = listOf(
    ProfileEntry("Воспроизведение", "Качество, язык, субтитры", Icons.Outlined.PlayCircleOutline, "playback"),
    ProfileEntry("Загрузки", "Офлайн и мобильная сеть", Icons.Outlined.Download, "downloads"),
    ProfileEntry("Уведомления", "Локальные уведомления", Icons.Outlined.NotificationsNone, "notifications"),
    ProfileEntry("Внешний вид", "Тёмная, светлая или системная тема", Icons.Outlined.Palette, "appearance"),
    ProfileEntry("Доступность", "Контраст и требования интерфейса", Icons.Outlined.AccessibilityNew, "accessibility"),
    ProfileEntry("Устройства", "Текущее устройство и синхронизация", Icons.Outlined.Devices, "devices"),
    ProfileEntry("Помощь", "Справка по Viora", Icons.AutoMirrored.Outlined.HelpOutline, "help"),
)

@Composable
fun ProfileScreen(
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
    onOpenSettings: (String) -> Unit,
) {
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(
            start = 16.dp,
            top = contentPadding.calculateTopPadding() + 20.dp,
            end = 16.dp,
            bottom = contentPadding.calculateBottomPadding() + 24.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text("Профиль", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 8.dp))
        }
        item {
            Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Box(
                        modifier = Modifier.size(56.dp).background(MaterialTheme.colorScheme.surfaceVariant, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) { Icon(Icons.Outlined.Person, contentDescription = null, tint = MaterialTheme.colorScheme.primary) }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("Локальный профиль", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text("Прогресс и настройки хранятся на устройстве", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text("Локально", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        items(profileEntries.size) { index ->
            val entry = profileEntries[index]
            Card(
                modifier = Modifier.fillMaxWidth().clickable { onOpenSettings(entry.route) },
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            ) {
                ListItem(
                    headlineContent = { Text(entry.title) },
                    supportingContent = { Text(entry.subtitle) },
                    leadingContent = { Icon(entry.icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                    trailingContent = { Icon(Icons.Outlined.ChevronRight, contentDescription = null) },
                )
            }
        }
        item {
            Text("Viora 0.2.0", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
        }
    }
}
