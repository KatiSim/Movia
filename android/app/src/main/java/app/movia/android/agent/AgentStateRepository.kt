package app.movia.android.agent

import android.content.Context
import android.content.pm.PackageManager
import android.os.SystemClock
import app.movia.android.data.catalog.DemoCatalogRepository
import app.movia.android.domain.model.PlaybackState
import app.movia.android.ui.player.MoviaPlaybackRegistry
import org.json.JSONArray
import org.json.JSONObject

class AgentStateRepository(
    private val context: Context,
    private val events: AgentEventBus,
) {
    private val startedAtElapsedMs = SystemClock.elapsedRealtime()

    @Volatile private var foreground: Boolean = false
    @Volatile private var uiAttached: Boolean = false
    @Volatile private var currentScreen: String = "HEADLESS"
    @Volatile private var playerOpen: Boolean = false
    @Volatile private var settingsOpen: Boolean = false
    @Volatile private var fullscreen: Boolean = false
    @Volatile private var pip: Boolean = false
    @Volatile private var fullscreenAvailable: Boolean = false
    @Volatile private var pipAvailable: Boolean = false
    @Volatile private var lastError: JSONObject? = null
    @Volatile private var catalogSyncRunning: Boolean = false

    private val settingValues = linkedMapOf<String, Any?>(
        "appearance.themeMode" to "DARK",
        "accessibility.highContrast" to false,
        "player.showSeekButtons" to false,
        "notifications.enabled" to true,
        "player.audio" to "Auto",
        "player.quality" to "Auto",
        "player.subtitlesEnabled" to false,
        "player.autoNext" to true,
        "downloads.wifiOnly" to true,
    )

    private val settingMetadata = linkedMapOf(
        "appearance.themeMode" to SettingMeta("Theme mode", "string", "DARK", listOf("DARK", "LIGHT", "SYSTEM")),
        "accessibility.highContrast" to SettingMeta("High contrast", "boolean", false),
        "player.showSeekButtons" to SettingMeta("Persistent seek buttons", "boolean", false),
        "notifications.enabled" to SettingMeta("Notifications enabled", "boolean", true),
        "player.audio" to SettingMeta("Preferred audio/voice", "string", "Auto"),
        "player.quality" to SettingMeta("Preferred quality", "string", "Auto"),
        "player.subtitlesEnabled" to SettingMeta("Subtitles enabled", "boolean", false),
        "player.autoNext" to SettingMeta("Automatic next episode", "boolean", true),
        "downloads.wifiOnly" to SettingMeta("Downloads on Wi-Fi only", "boolean", true),
    )

    private val libraryLock = Any()
    private var favorites: Set<String> = emptySet()
    private var watchLater: Set<String> = emptySet()
    private var history: List<String> = emptyList()
    private var downloads: Set<String> = emptySet()
    private var recentSearches: List<String> = emptyList()

    fun setUiAttached(value: Boolean) {
        val changed = uiAttached != value
        uiAttached = value
        if (!value) {
            foreground = false
            currentScreen = "HEADLESS"
            playerOpen = false
            settingsOpen = false
            fullscreen = false
            pip = false
        }
        if (changed) {
            events.publish("UI_ATTACHMENT_CHANGED", details = mapOf("attached" to value))
        }
    }

    fun updateNavigation(
        screen: String,
        playerOpen: Boolean,
        settingsOpen: Boolean = false,
    ) {
        uiAttached = true
        val changed = currentScreen != screen || this.playerOpen != playerOpen || this.settingsOpen != settingsOpen
        currentScreen = screen
        this.playerOpen = playerOpen
        this.settingsOpen = settingsOpen
        if (changed) {
            events.publish(
                "APP_SCREEN_CHANGED",
                details = mapOf(
                    "screen" to screen,
                    "playerOpen" to playerOpen,
                    "settingsOpen" to settingsOpen,
                ),
            )
        }
    }

    fun updateForeground(value: Boolean) {
        foreground = value
    }

    fun updatePresentation(fullscreen: Boolean, pip: Boolean) {
        this.fullscreen = fullscreen
        this.pip = pip
    }

    fun updateSystemHandlers(fullscreen: Boolean, pip: Boolean) {
        fullscreenAvailable = fullscreen
        pipAvailable = pip
    }

    fun updateCatalogSync(running: Boolean) {
        catalogSyncRunning = running
    }

    fun updateSetting(key: String, value: Any?) {
        synchronized(settingValues) {
            if (settingMetadata.containsKey(key)) {
                settingValues[key] = value ?: JSONObject.NULL
            }
        }
    }

    fun updateLibrary(
        favorites: Set<String>? = null,
        watchLater: Set<String>? = null,
        history: List<String>? = null,
        downloads: Set<String>? = null,
        recentSearches: List<String>? = null,
    ) {
        synchronized(libraryLock) {
            favorites?.let { this.favorites = it.toSet() }
            watchLater?.let { this.watchLater = it.toSet() }
            history?.let { this.history = it.toList() }
            downloads?.let { this.downloads = it.toSet() }
            recentSearches?.let { this.recentSearches = it.toList() }
        }
    }

    fun setLastError(code: String, message: String, context: Map<String, Any?> = emptyMap()) {
        lastError = JSONObject().apply {
            put("code", code)
            put("message", message)
            put("retryable", true)
            put("context", JSONObject().apply {
                context.forEach { (key, value) -> put(key, value ?: JSONObject.NULL) }
            })
        }
    }

    fun clearLastError() {
        lastError = null
    }

    fun currentSettingsJson(): JSONArray = synchronized(settingValues) {
        JSONArray().apply {
            settingMetadata.forEach { (key, meta) ->
                val value = settingValues[key]
                put(JSONObject().apply {
                    put("key", key)
                    put("label", meta.label)
                    put("type", meta.type)
                    put("value", value ?: JSONObject.NULL)
                    put("default", meta.defaultValue)
                    put("mutable", true)
                    if (meta.allowedValues.isNotEmpty()) put("allowedValues", JSONArray(meta.allowedValues))
                })
            }
        }
    }

    fun libraryJson(): JSONObject = synchronized(libraryLock) {
        JSONObject().apply {
            put("favorites", JSONArray(favorites.sorted()))
            put("watchLater", JSONArray(watchLater.sorted()))
            put("history", JSONArray(history))
            put("downloads", JSONArray(downloads.sorted()))
            put("recentSearches", JSONArray(recentSearches))
            put("counts", JSONObject()
                .put("favorites", favorites.size)
                .put("watchLater", watchLater.size)
                .put("history", history.size)
                .put("downloads", downloads.size)
                .put("recentSearches", recentSearches.size))
        }
    }

    fun snapshotJson(): JSONObject {
        val session = MoviaPlaybackRegistry.current
        val state = session?.state?.value ?: PlaybackState()
        val selection = state.activeStreamSelection
        val playback = JSONObject().apply {
            put("status", state.status.name)
            put("switchState", state.switchState.name)
            put("playing", state.isPlaying)
            put("playWhenReady", state.playWhenReady)
            put("mediaId", state.mediaId)
            put("title", state.displayTitle)
            putOpt("season", state.seasonNumber)
            putOpt("episode", state.episodeNumber)
            put("positionMs", state.currentPositionMs)
            put("durationMs", state.totalDurationMs)
            put("bufferedMs", state.bufferedPositionMs)
            put("requestedStreamId", selection?.requestedStreamId)
            put("activeStreamId", selection?.activeStreamId)
            put("requestedQuality", selection?.requestedQuality)
            put("requestedVoice", selection?.requestedVoice)
            put("activeQuality", selection?.activeQuality)
            put("activeVoice", selection?.activeVoice)
            put("quality", selection?.activeQuality ?: selection?.requestedQuality)
            put("voice", selection?.activeVoice ?: selection?.requestedVoice)
            put("source", selection?.source)
            put("fallbackReason", selection?.fallbackReason)
            put("mediaItemId", state.mediaId)
            put("fullscreen", fullscreen)
            put("pip", pip)
            putOpt("statusMessage", state.statusMessage)
        }
        val packageInfo = try {
            context.packageManager.getPackageInfo(context.packageName, 0)
        } catch (_: PackageManager.NameNotFoundException) {
            null
        }
        val versionName = packageInfo?.versionName ?: "unknown"
        val versionCode = packageInfo?.longVersionCode ?: 0L
        val library = libraryJson()
        return JSONObject().apply {
            put("schemaVersion", MOVIA_AGENT_SCHEMA_VERSION)
            put("agentApiVersion", MOVIA_AGENT_SCHEMA_VERSION)
            put("app", JSONObject().apply {
                put("package", context.packageName)
                put("version", versionName)
                put("versionCode", versionCode)
                put("foreground", foreground)
                put("processAlive", true)
                put("processUptimeMs", SystemClock.elapsedRealtime() - startedAtElapsedMs)
                put("uiAttached", uiAttached)
                put("screen", if (uiAttached) currentScreen else "HEADLESS")
            })
            put("navigation", JSONObject().apply {
                put("uiAttached", uiAttached)
                put("topLevel", if (uiAttached) currentScreen else JSONObject.NULL)
                put("playerOpen", playerOpen)
                put("settingsOpen", settingsOpen)
            })
            put("playback", playback)
            put("catalog", JSONObject().apply {
                put("syncRunning", catalogSyncRunning)
                put("cachedTotal", runCatching { DemoCatalogRepository.all().size }.getOrDefault(0))
            })
            put("network", JSONObject().apply {
                put("snapshotPolicy", "read_hot_state_only")
                put("networkProbePerformed", false)
            })
            put("library", library)
            put("downloads", JSONObject().apply {
                put("stored", library.optJSONObject("counts")?.optInt("downloads", 0) ?: 0)
                put("titles", library.optJSONArray("downloads") ?: JSONArray())
            })
            put("settings", currentSettingsJson())
            put("errors", JSONArray().apply {
                lastError?.let(::put)
                state.statusMessage?.let {
                    if (state.status.name == "FAILED") {
                        put(JSONObject().put("code", "PLAYBACK_FAILED").put("message", it))
                    }
                }
            })
        }
    }

    fun actionsJson(): JSONArray {
        val hasMedia = MoviaPlaybackRegistry.current?.state?.value?.hasMedia == true
        val hasStreams = MoviaPlaybackRegistry.current?.streamOptions?.value?.isNotEmpty() == true
        val hasDownloads = synchronized(libraryLock) { downloads.isNotEmpty() }
        return JSONArray().apply {
            AgentActionRegistry.definitions.forEach { definition ->
                val enabled = when {
                    definition.requiresUi && !uiAttached -> false
                    definition.id in setOf("player.play", "player.pause", "player.toggle", "player.stop", "player.seek", "player.seekRelative", "player.nextEpisode", "player.previousEpisode") -> hasMedia
                    definition.id in setOf("player.getStreams", "player.selectStream", "player.selectQuality", "player.selectVoice") -> hasStreams
                    definition.id == "player.enterFullscreen" || definition.id == "player.exitFullscreen" -> hasMedia && uiAttached && fullscreenAvailable
                    definition.id == "player.enterPip" -> hasMedia && uiAttached && pipAvailable
                    definition.id == "downloads.deleteAll" -> hasDownloads
                    else -> true
                }
                put(JSONObject().apply {
                    put("id", definition.id)
                    put("safety", definition.safety.name)
                    put("description", definition.description)
                    put("enabled", enabled)
                    put("requiresUi", definition.requiresUi)
                    if (!enabled) put("reason", when {
                        definition.requiresUi && !uiAttached -> "UI_NOT_ATTACHED"
                        definition.id == "downloads.deleteAll" -> "NO_DOWNLOADS"
                        definition.id in setOf("player.getStreams", "player.selectStream", "player.selectQuality", "player.selectVoice") -> "NO_STREAMS"
                        else -> "NO_ACTIVE_MEDIA"
                    })
                    put("schema", definition.schema)
                })
            }
        }
    }

    fun uiTreeJson(): JSONObject {
        val snapshot = snapshotJson()
        val playback = snapshot.optJSONObject("playback")
        val hasMedia = playback?.optString("mediaId").orEmpty().isNotBlank()
        val playing = playback?.optBoolean("playing", false) == true
        val root = JSONObject().apply {
            put("schemaVersion", MOVIA_AGENT_SCHEMA_VERSION)
            put("attached", uiAttached)
            put("visible", uiAttached)
            put("headless", !uiAttached)
            put("surface", if (uiAttached) "VISIBLE_UI" else "HEADLESS")
            put("screen", if (uiAttached) currentScreen else "HEADLESS")
            put("nodes", JSONArray())
            put("headlessCapabilities", headlessCapabilitiesJson())
        }
        if (!uiAttached) return root
        val nodes = root.getJSONArray("nodes")
        val topLevelNavigationVisible = !playerOpen && currentScreen in setOf("HOME", "CATALOG", "LIBRARY")
        if (topLevelNavigationVisible) {
            nodes.put(node("navigation.home", "Home", "navigation.home", true, testTag = "navigation.home"))
            nodes.put(node("navigation.catalog", "Catalog", "navigation.catalog", true, testTag = "navigation.catalog"))
            nodes.put(node("navigation.library", "Library", "navigation.library", true, testTag = "navigation.library"))
        }
        if (playerOpen && hasMedia) {
            val fullscreenAction = if (fullscreen) "player.exitFullscreen" else "player.enterFullscreen"
            nodes.put(node("player.back", "Back", "navigation.back", true, testTag = "player.back"))
            nodes.put(node("player.playPause", if (playing) "Pause" else "Play", if (playing) "player.pause" else "player.play", true))
            nodes.put(node("player.settings", "Playback settings", "player.openSettings", true, testTag = "player.settings"))
            nodes.put(node("player.timeline", "Timeline", "player.seek", true, role = "slider", testTag = "player.timeline"))
            nodes.put(node("player.fullscreen", "Fullscreen", fullscreenAction, fullscreenAvailable, if (fullscreenAvailable) null else "SYSTEM_HANDLER_NOT_BOUND", testTag = "player.fullscreen"))
            nodes.put(node("player.pip", "Picture in picture", "player.enterPip", pipAvailable, if (pipAvailable) null else "SYSTEM_HANDLER_NOT_BOUND", testTag = "player.pip"))
        }
        return root
    }

    fun controlsManifestJson(): JSONObject {
        val fullscreenAction = if (fullscreen) "player.exitFullscreen" else "player.enterFullscreen"
        return JSONObject().apply {
            put("schemaVersion", MOVIA_AGENT_SCHEMA_VERSION)
            put("description", "Stable logical control manifest. Static test tags are emitted only when the named UI applies them; dynamic controls expose a tag pattern when one exists.")
            put("controls", JSONArray().apply {
                listOf(
                    Control("navigation.home", "button", "navigation.home", testTag = "navigation.home"),
                    Control("navigation.catalog", "button", "navigation.catalog", testTag = "navigation.catalog"),
                    Control("navigation.library", "button", "navigation.library", testTag = "navigation.library"),
                    Control("home.hero.open", "button", "media.details", dynamic = true),
                    Control("home.hero.play", "button", "media.play", dynamic = true),
                    Control("home.section.item.open", "button", "media.details", dynamic = true),
                    Control("catalog.search", "textField", "catalog.query"),
                    Control("catalog.filter", "button", "catalog.query"),
                    Control("catalog.item.open", "button", "media.details", dynamic = true),
                    Control("catalog.searchHistory.clear", "button", "searchHistory.clear"),
                    Control("details.play", "button", "media.play", dynamic = true),
                    Control("details.favorite", "toggle", "library.setMyList", dynamic = true),
                    Control("details.download", "button", "downloads.enqueue", dynamic = true),
                    Control("details.seasons", "button", "media.details", dynamic = true),
                    Control("library.favorite.item", "button", "media.details", dynamic = true),
                    Control("library.download.item", "button", "media.details", dynamic = true),
                    Control("library.history.item", "button", "media.details", dynamic = true),
                    Control("library.history.clear", "button", "history.clear"),
                    Control("player.back", "button", "navigation.back", testTag = "player.back"),
                    Control("player.playPause", "button", "player.toggle"),
                    Control("player.seekBack", "button", "player.seekRelative"),
                    Control("player.seekForward", "button", "player.seekRelative"),
                    Control("player.timeline", "slider", "player.seek", testTag = "player.timeline"),
                    Control("player.nextEpisode", "button", "player.nextEpisode"),
                    Control("player.previousEpisode", "button", "player.previousEpisode"),
                    Control("player.settings", "button", "player.openSettings", testTag = "player.settings"),
                    Control("player.fullscreen", "button", fullscreenAction, testTag = "player.fullscreen"),
                    Control("player.pip", "button", "player.enterPip", testTag = "player.pip"),
                    Control("player.quality", "selection", "player.selectQuality", dynamic = true, testTagPattern = "settings.quality.{normalizedValue}"),
                    Control("player.voice", "selection", "player.selectVoice", dynamic = true, testTagPattern = "settings.voice.{normalizedValue}"),
                    Control("settings.theme", "selection", "settings.set"),
                    Control("settings.highContrast", "switch", "settings.set"),
                    Control("settings.seekButtons", "switch", "settings.set", testTag = "settings.player.showSeekButtons"),
                    Control("settings.subtitles", "switch", "settings.set"),
                    Control("settings.autoNext", "switch", "settings.set", testTag = "settings.player.autoNext"),
                    Control("settings.wifiOnly", "switch", "settings.set"),
                ).forEach { control ->
                    put(JSONObject()
                        .put("id", control.id)
                        .put("role", control.role)
                        .put("actionId", control.actionId)
                        .put("dynamic", control.dynamic)
                        .apply {
                            control.testTag?.let { put("testTag", it) }
                            control.testTagPattern?.let { put("testTagPattern", it) }
                        })
                }
            })
        }
    }

    private fun headlessCapabilitiesJson(): JSONArray = JSONArray().apply {
        AgentActionRegistry.definitions
            .filter { !it.requiresUi }
            .forEach { definition ->
                put(JSONObject()
                    .put("id", definition.id)
                    .put("actionId", definition.id)
                    .put("visible", false)
                    .put("headless", true))
            }
    }

    private fun node(
        id: String,
        name: String,
        actionId: String,
        enabled: Boolean,
        reason: String? = null,
        role: String = "button",
        testTag: String? = null,
    ): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("actionId", actionId)
        put("role", role)
        put("enabled", enabled)
        put("visible", true)
        put("headless", false)
        testTag?.let { put("testTag", it) }
        reason?.let { put("reason", it) }
    }

    private data class SettingMeta(
        val label: String,
        val type: String,
        val defaultValue: Any,
        val allowedValues: List<String> = emptyList(),
    )

    private data class Control(
        val id: String,
        val role: String,
        val actionId: String,
        val dynamic: Boolean = false,
        val testTag: String? = null,
        val testTagPattern: String? = null,
    )
}
