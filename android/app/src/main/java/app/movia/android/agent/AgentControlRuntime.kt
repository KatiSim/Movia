package app.movia.android.agent

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import app.movia.android.data.catalog.CatalogFilter
import app.movia.android.data.catalog.CatalogSort
import app.movia.android.data.catalog.DemoCatalogRepository
import app.movia.android.data.catalog.filterCatalog
import app.movia.android.data.catalog.searchCatalogLocally
import app.movia.android.data.catalog.sortCatalog
import app.movia.android.data.download.DownloadScheduler
import app.movia.android.data.library.LibraryRepository
import app.movia.android.data.preferences.MoviaPreferencesRepository
import app.movia.android.domain.model.CatalogCategory
import app.movia.android.domain.model.ContentType
import app.movia.android.domain.model.ActiveStreamSelection
import app.movia.android.domain.model.MediaContent
import app.movia.android.domain.model.PlaybackState
import app.movia.android.domain.model.StreamOption
import app.movia.android.ui.player.MoviaPlaybackRegistry
import app.movia.android.ui.player.PlaybackSession
import androidx.media3.common.Player
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

object AgentControlRuntime {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val lock = Any()
    private var appContext: Context? = null
    private var stateRepository: AgentStateRepository? = null
    private var eventBus: AgentEventBus? = null
    private var controlService: AgentControlService? = null
    private var preferences: MoviaPreferencesRepository? = null
    private var libraryRepository: LibraryRepository? = null
    private var scope: CoroutineScope? = null
    private var monitorJob: Job? = null
    private val operationStore = AgentOperationStore(256)
    private val selectionLock = Any()
    private var selectionGeneration = 0L
    private var latestSelectionOperationId: String? = null

    private data class SelectionOperationToken(
        val generation: Long,
        val operationId: String,
    )

    private data class StartedSelectionOperation(
        val operation: AgentOperation,
        val token: SelectionOperationToken,
        val superseded: AgentOperation?,
    )

    @Volatile private var onNavigate: ((String) -> Unit)? = null
    @Volatile private var onOpenPlayerSettings: (() -> Unit)? = null
    @Volatile private var onClosePlayerSettings: (() -> Unit)? = null
    @Volatile private var onEnterFullscreen: (() -> Unit)? = null
    @Volatile private var onExitFullscreen: (() -> Unit)? = null
    @Volatile private var onEnterPip: (() -> Unit)? = null

    fun start(context: Context) {
        synchronized(lock) {
            if (controlService != null) return
            Log.i("MoviaAgent", "runtime start")
            val applicationContext = context.applicationContext
            appContext = applicationContext
            DemoCatalogRepository.init(applicationContext)
            val events = AgentEventBus(1000)
            val state = AgentStateRepository(applicationContext, events)
            val prefs = MoviaPreferencesRepository(applicationContext)
            val library = LibraryRepository(applicationContext)
            eventBus = events
            stateRepository = state
            preferences = prefs
            libraryRepository = library
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            controlService = AgentControlService(
                applicationContext,
                state,
                events,
            ) { request -> dispatch(request) }.also { it.start() }
            startHotStateCollectors(state, prefs, library)
            monitorJob = scope?.launch { monitorPlayback() }
            events.publish(
                "AGENT_BRIDGE_STARTED",
                details = mapOf(
                    "port" to MOVIA_AGENT_PORT,
                    "loopbackOnly" to true,
                    "schemaVersion" to MOVIA_AGENT_SCHEMA_VERSION,
                    "headless" to true,
                ),
            )
        }
    }

    private fun startHotStateCollectors(
        state: AgentStateRepository,
        prefs: MoviaPreferencesRepository,
        library: LibraryRepository,
    ) {
        scope?.launch {
            prefs.appPreferences.collect { value ->
                state.updateSetting("appearance.themeMode", value.themeMode)
                state.updateSetting("accessibility.highContrast", value.highContrast)
                state.updateSetting("player.showSeekButtons", value.persistentSeekButtons)
                state.updateSetting("notifications.enabled", value.notificationsEnabled)
            }
        }
        scope?.launch {
            prefs.playbackPreferences.collect { value ->
                state.updateSetting("player.audio", value.audio)
                state.updateSetting("player.quality", value.quality)
                state.updateSetting("player.subtitlesEnabled", value.subtitlesEnabled)
                state.updateSetting("player.autoNext", value.autoNextEnabled)
                state.updateSetting("downloads.wifiOnly", value.wifiOnlyDownloads)
            }
        }
        scope?.launch { library.favorites.collect { state.updateLibrary(favorites = it) } }
        scope?.launch { library.watchLater.collect { state.updateLibrary(watchLater = it) } }
        scope?.launch { library.history.collect { state.updateLibrary(history = it) } }
        scope?.launch { library.downloads.collect { state.updateLibrary(downloads = it) } }
        scope?.launch { library.recentSearches.collect { state.updateLibrary(recentSearches = it) } }
    }

    fun replaceBridgeToken(token: String): Boolean {
        if (!Regex("^[0-9a-fA-F]{64}$").matches(token)) return false
        val replaced = controlService?.replaceToken(token.lowercase()) ?: false
        if (replaced) {
            eventBus?.publish("AGENT_TOKEN_ROTATED", details = mapOf("source" to "authorized_bootstrap"))
        }
        return replaced
    }

    fun updateForeground(value: Boolean) {
        stateRepository?.updateForeground(value)
    }

    fun updateNavigation(screen: String, playerOpen: Boolean, settingsOpen: Boolean = false) {
        stateRepository?.updateNavigation(screen, playerOpen, settingsOpen)
    }

    fun updatePlayerSettingsOpen(value: Boolean) {
        stateRepository?.updateNavigation(
            screen = if (value) "PLAYER_SETTINGS" else "PLAYER",
            playerOpen = true,
            settingsOpen = value,
        )
    }

    fun updatePresentation(fullscreen: Boolean, pip: Boolean) {
        stateRepository?.updatePresentation(fullscreen, pip)
    }

    fun updateSetting(key: String, value: Any?) {
        stateRepository?.updateSetting(key, value)
    }

    fun registerUiHandlers(
        playTitle: ((String) -> Unit)? = null,
        navigate: ((String) -> Unit)? = null,
        nextEpisode: (() -> Unit)? = null,
    ) {
        // Legacy callbacks remain accepted for source compatibility, but domain actions no longer depend on them.
        @Suppress("UNUSED_VARIABLE") val ignoredPlay = playTitle
        @Suppress("UNUSED_VARIABLE") val ignoredNext = nextEpisode
        onNavigate = navigate
        stateRepository?.setUiAttached(true)
    }

    fun registerPlayerSettingsHandlers(
        open: (() -> Unit)?,
        close: (() -> Unit)?,
        enterFullscreen: (() -> Unit)? = null,
        exitFullscreen: (() -> Unit)? = null,
        enterPip: (() -> Unit)? = null,
    ) {
        onOpenPlayerSettings = open
        onClosePlayerSettings = close
        onEnterFullscreen = enterFullscreen
        onExitFullscreen = exitFullscreen
        onEnterPip = enterPip
        stateRepository?.updateSystemHandlers(enterFullscreen != null, enterPip != null)
    }

    fun clearUiHandlers() {
        onNavigate = null
        onOpenPlayerSettings = null
        onClosePlayerSettings = null
        onEnterFullscreen = null
        onExitFullscreen = null
        onEnterPip = null
        stateRepository?.updateSystemHandlers(false, false)
        stateRepository?.setUiAttached(false)
    }

    fun healthJson(context: Context): JSONObject = JSONObject().apply {
        put("schemaVersion", MOVIA_AGENT_SCHEMA_VERSION)
        put("agentApiVersion", MOVIA_AGENT_SCHEMA_VERSION)
        put("status", if (controlService != null) "ok" else "stopped")
        put("package", context.packageName)
        put("port", MOVIA_AGENT_PORT)
        put("bindAddress", "127.0.0.1")
        put("authenticated", true)
        put("uiRequiredForRead", false)
        put("uiRequiredForDomainActions", false)
        put("headlessBootstrap", true)
        put("shizukuRequired", false)
        put("processAlive", true)
    }

    fun capabilitiesJson(): JSONObject = JSONObject().apply {
        put("schemaVersion", MOVIA_AGENT_SCHEMA_VERSION)
        put("capabilities", JSONArray().apply {
            put("hotSnapshot")
            put("headlessBootstrap")
            put("domainPlayback")
            put("catalog")
            put("library")
            put("downloads")
            put("streamSelection")
            put("operationTracking")
            put("events")
            put("sse")
            put("localBridge")
            put("settings")
            put("diagnostics")
            put("logicalUiManifest")
        })
        put("pip", onEnterPip != null)
        put("fullscreen", onEnterFullscreen != null)
        put("screenshots", "external_fallback_only")
        put("normalActionsRequireUi", false)
        put("normalActionsRequireShizuku", false)
    }

    fun manifestJson(): JSONObject = JSONObject().apply {
        put("schemaVersion", MOVIA_AGENT_SCHEMA_VERSION)
        put("name", "Movia")
        put("description", "Agent-native local control plane for Movia")
        put("transport", "http-loopback")
        put("baseUrl", "http://127.0.0.1:$MOVIA_AGENT_PORT/agent/v1")
        put("bootstrap", JSONObject()
            .put("type", "explicit-broadcast")
            .put("action", AgentBootstrapReceiver.ACTION_BOOTSTRAP)
            .put("component", "app.movia.android/.agent.AgentBootstrapReceiver")
            .put("visibleActivityRequired", false)
            .put("wakeOnly", true)
            .put("credentialTransfer", false)
            .put("authorization", "bearer-token-from-app-private-storage"))
        put("capabilities", capabilitiesJson().optJSONArray("capabilities"))
        put("actions", stateRepository?.actionsJson() ?: JSONArray())
        put("endpoints", JSONArray().apply {
            put("/agent/v1/health")
            put("/agent/v1/snapshot")
            put("/agent/v1/actions")
            put("/agent/v1/ui")
            put("/agent/v1/ui/controls")
            put("/agent/v1/events")
            put("/agent/v1/events/stream")
            put("/agent/v1/settings")
            put("/agent/v1/streams")
            put("/agent/v1/diagnostics")
            put("/agent/v1/capabilities")
            put("/agent/v1/manifest")
            put("/agent/v1/operations")
            put("/agent/v1/action")
        })
    }

    fun streamsJson(): JSONObject {
        val session = MoviaPlaybackRegistry.current
        val streams = session?.streamOptions?.value.orEmpty()
        val current = session?.state?.value?.activeStreamSelection
        val qualities = JSONObject()
        streams.forEach { stream ->
            val quality = stream.quality.ifBlank { "Не указано" }
            val voices = qualities.optJSONArray(quality) ?: JSONArray().also { qualities.put(quality, it) }
            voices.put(streamJson(stream, current?.requestedStreamId, current?.activeStreamId))
        }
        return JSONObject().apply {
            put("schemaVersion", MOVIA_AGENT_SCHEMA_VERSION)
            put("requestedStreamId", current?.requestedStreamId)
            put("activeStreamId", current?.activeStreamId)
            put("requestedQuality", current?.requestedQuality)
            put("requestedVoice", current?.requestedVoice)
            put("activeQuality", current?.activeQuality)
            put("activeVoice", current?.activeVoice)
            put("fallbackReason", current?.fallbackReason)
            put("qualities", JSONArray().apply {
                val iterator = qualities.keys()
                while (iterator.hasNext()) {
                    val quality = iterator.next()
                    put(JSONObject().put("quality", quality).put("voices", qualities.optJSONArray(quality)))
                }
            })
        }
    }

    fun diagnosticsJson(): JSONObject {
        val session = MoviaPlaybackRegistry.current
        val state = session?.state?.value

        var mediaItemId: String? = state?.mediaId
        var uriScheme: String? = null
        var uriHost: String? = null
        var uriPath: String? = null
        var videoWidth = 0
        var videoHeight = 0
        var bufferedPositionMs = 0L
        var currentPositionMs = 0L
        var actualPlaybackState = "IDLE"
        var actualPlaybackStateCode = Player.STATE_IDLE
        var actualIsPlaying = false
        var actualPlayWhenReady = false
        var playbackSuppressionReason = 0
        var playerErrorCode: String? = null
        var playerErrorCause: String? = null

        // Media3 enforces application-thread access for player getters. The
        // agent HTTP server runs on a pool thread, so snapshot only the
        // player-owned fields through the existing main-thread bridge.
        if (session != null) {
            runOnMain {
                val player = session.player
                val mediaItem = player.currentMediaItem
                val uri = mediaItem?.localConfiguration?.uri
                mediaItemId = mediaItem?.mediaId ?: state?.mediaId
                uriScheme = uri?.scheme
                uriHost = uri?.host
                uriPath = uri?.path
                videoWidth = player.videoSize.width
                videoHeight = player.videoSize.height
                bufferedPositionMs = player.bufferedPosition
                currentPositionMs = player.currentPosition
                actualPlaybackStateCode = player.playbackState
                actualPlaybackState = when (player.playbackState) {
                    Player.STATE_IDLE -> "IDLE"
                    Player.STATE_BUFFERING -> "BUFFERING"
                    Player.STATE_READY -> "READY"
                    Player.STATE_ENDED -> "ENDED"
                    else -> player.playbackState.toString()
                }
                actualIsPlaying = player.isPlaying
                actualPlayWhenReady = player.playWhenReady
                playbackSuppressionReason = player.playbackSuppressionReason
                playerErrorCode = player.playerError?.errorCodeName
                playerErrorCause = player.playerError?.cause?.javaClass?.simpleName
            }
        }

        return JSONObject().apply {
            put("schemaVersion", MOVIA_AGENT_SCHEMA_VERSION)
            put("snapshot", stateRepository?.snapshotJson() ?: JSONObject())
            put("media3", JSONObject().apply {
                put("playbackState", actualPlaybackState)
                put("playbackStateCode", actualPlaybackStateCode)
                put("domainPlaybackState", state?.status?.name ?: "IDLE")
                put("switchState", state?.switchState?.name ?: "IDLE")
                put("playWhenReady", actualPlayWhenReady)
                put("isPlaying", actualIsPlaying)
                put("playbackSuppressionReason", playbackSuppressionReason)
                put("playerErrorCode", playerErrorCode)
                put("playerErrorCause", playerErrorCause)
                put("mediaItemId", mediaItemId)
                put("uriScheme", uriScheme)
                put("uriHost", uriHost)
                put("uriPath", uriPath)
                put("videoWidth", videoWidth)
                put("videoHeight", videoHeight)
                put("bufferedPositionMs", bufferedPositionMs)
                put("currentPositionMs", currentPositionMs)
            })
            put("streamSelection", JSONObject().apply {
                val selection = state?.activeStreamSelection
                put("requestedStreamId", selection?.requestedStreamId)
                put("activeStreamId", selection?.activeStreamId)
                put("requestedQuality", selection?.requestedQuality)
                put("requestedVoice", selection?.requestedVoice)
                put("activeQuality", selection?.activeQuality)
                put("activeVoice", selection?.activeVoice)
                put("source", selection?.source)
                put("fallbackReason", selection?.fallbackReason)
            })
            put("events", JSONArray().apply {
                eventBus?.snapshot(100)?.forEach { put(it.toJson()) }
            })
            put("backend", JSONObject()
                .put("policy", "no_network_probe_in_snapshot")
                .put("catalog", "query_on_demand")
                .put("resolver", "query_on_demand")
                .put("streamProxy", "player_observed"))
        }
    }

    fun operationJson(operationId: String): JSONObject? = operationStore.get(operationId)?.toJson()

    private fun beginSelectionOperation(
        action: String,
        requestId: String,
        requested: Map<String, Any?>,
    ): StartedSelectionOperation {
        val started = synchronized(selectionLock) {
            val previous = latestSelectionOperationId?.let { operationStore.get(it) }
            val operation = operationStore.create(action, requestId, requested)
            val generation = ++selectionGeneration
            latestSelectionOperationId = operation.operationId
            StartedSelectionOperation(
                operation = operation,
                token = SelectionOperationToken(generation, operation.operationId),
                superseded = previous,
            )
        }
        started.superseded?.let { supersedeSelectionOperation(it, started.operation) }
        return started
    }

    private fun supersedeSelectionOperation(previous: AgentOperation, newer: AgentOperation) {
        val current = operationStore.get(previous.operationId) ?: return
        if (current.status != AgentOperationStatus.ACCEPTED && current.status != AgentOperationStatus.RUNNING) return
        operationStore.fail(
            previous.operationId,
            "SELECTION_SUPERSEDED",
            "Superseded by newer selection operation ${newer.operationId}",
        )?.let { failed ->
            publishOperation("OPERATION_FAILED", failed)
            eventBus?.publish(
                "COMMAND_FAILED",
                failed.requestId,
                mapOf(
                    "action" to failed.action,
                    "operationId" to failed.operationId,
                    "code" to failed.errorCode,
                    "supersededBy" to newer.operationId,
                ),
            )
        }
    }

    private fun isCurrentSelection(token: SelectionOperationToken): Boolean = synchronized(selectionLock) {
        latestSelectionOperationId == token.operationId && selectionGeneration == token.generation
    }

    private fun markSelectionRunning(token: SelectionOperationToken): AgentOperation? = synchronized(selectionLock) {
        if (latestSelectionOperationId != token.operationId || selectionGeneration != token.generation) return@synchronized null
        val current = operationStore.get(token.operationId)
        if (current == null || current.status != AgentOperationStatus.ACCEPTED) return@synchronized null
        operationStore.running(token.operationId)
    }

    private fun failSelectionIfCurrent(
        token: SelectionOperationToken,
        code: String,
        message: String,
    ): AgentOperation? = synchronized(selectionLock) {
        if (latestSelectionOperationId != token.operationId || selectionGeneration != token.generation) return@synchronized null
        val current = operationStore.get(token.operationId)
        if (current == null || (current.status != AgentOperationStatus.ACCEPTED && current.status != AgentOperationStatus.RUNNING)) {
            return@synchronized null
        }
        operationStore.fail(token.operationId, code, message)
    }

    private fun completeSelectionIfCurrent(
        token: SelectionOperationToken,
        session: PlaybackSession,
        expectedMediaId: String,
        expectedStreamId: String?,
        expectedQuality: String?,
        expectedVoice: String?,
        actualPlaybackEvidence: Boolean,
        persistTitle: String?,
        result: (PlaybackState, ActiveStreamSelection?) -> Map<String, Any?>,
    ): AgentOperation? = synchronized(selectionLock) {
        if (latestSelectionOperationId != token.operationId || selectionGeneration != token.generation) return@synchronized null
        val current = operationStore.get(token.operationId)
        if (current == null || (current.status != AgentOperationStatus.ACCEPTED && current.status != AgentOperationStatus.RUNNING)) {
            return@synchronized null
        }
        if (!isCurrentPlaybackSession(session)) return@synchronized null
        val state = session.state.value
        val selection = state.activeStreamSelection
        if (!actualPlaybackEvidence || state.status.name == "FAILED" || state.mediaId != expectedMediaId || state.switchState.name != "READY") return@synchronized null
        if (!activeSelectionMatches(selection, expectedStreamId, expectedQuality, expectedVoice)) return@synchronized null
        if (!persistTitle.isNullOrBlank()) {
            persistVariant(persistTitle, selection?.activeQuality, selection?.activeVoice)
        }
        operationStore.complete(token.operationId, result(state, selection))
    }

    fun executeMachineAction(
        action: String,
        arguments: JSONObject = JSONObject(),
        requestId: String = "system-agent-" + UUID.randomUUID(),
    ): JSONObject {
        if (controlService == null) {
            val context = appContext ?: return error("BRIDGE_NOT_STARTED", "Movia agent runtime is not initialized")
            start(context)
        }
        return dispatch(
            JSONObject()
                .put("action", action)
                .put("arguments", arguments)
                .put("requestId", requestId),
        )
    }

    private fun dispatch(request: JSONObject): JSONObject {
        val action = request.optString("action").trim()
        val requestId = request.optString("requestId").takeIf { it.isNotBlank() }
            ?: "agent-" + UUID.randomUUID()
        val args = request.optJSONObject("arguments") ?: JSONObject()
        val events = eventBus ?: return error("BRIDGE_NOT_STARTED", "Movia agent bridge is not started")
            .put("schemaVersion", MOVIA_AGENT_SCHEMA_VERSION)
            .put("requestId", requestId)
        val definition = AgentActionRegistry.find(action)
            ?: return error("UNKNOWN_ACTION", "Unknown action: $action", false)
                .put("schemaVersion", MOVIA_AGENT_SCHEMA_VERSION)
                .put("requestId", requestId)
        if (definition.requiresUi && stateRepository?.actionsJson()?.toJsonList()?.firstOrNull { it.optString("id") == action }?.optBoolean("enabled") != true) {
            return error("UI_NOT_ATTACHED", "This action requires a visible attached UI", true, mapOf("action" to action))
                .put("schemaVersion", MOVIA_AGENT_SCHEMA_VERSION)
                .put("requestId", requestId)
        }

        events.publish("COMMAND_RECEIVED", requestId, mapOf("action" to action))
        val result = runCatching { dispatchAction(action, args, requestId) }.getOrElse {
            error("COMMAND_FAILED", it.message ?: "Action failed", true)
        }
        when (result.optString("status")) {
            "failed" -> events.publish("COMMAND_FAILED", requestId, mapOf("action" to action, "code" to result.optString("code")))
            "accepted" -> events.publish("COMMAND_ACCEPTED", requestId, mapOf("action" to action, "operationId" to result.optString("operationId")))
            else -> events.publish("COMMAND_COMPLETED", requestId, mapOf("action" to action, "status" to result.optString("status")))
        }
        return result.put("schemaVersion", MOVIA_AGENT_SCHEMA_VERSION).put("requestId", requestId)
    }

    private fun dispatchAction(action: String, args: JSONObject, requestId: String): JSONObject {
        val session = MoviaPlaybackRegistry.current
        return when (action) {
            "app.snapshot" -> completed(action, "snapshot" to (stateRepository?.snapshotJson() ?: JSONObject()))
            "app.health" -> completed(action, "health" to healthJson(appContext ?: return error("BRIDGE_NOT_STARTED", "Movia agent bridge is not started")))
            "app.capabilities" -> completed(action, "capabilities" to capabilitiesJson())
            "ui.tree" -> completed(action, "tree" to (stateRepository?.uiTreeJson() ?: JSONObject()))
            "ui.controls" -> completed(action, "controls" to (stateRepository?.controlsManifestJson() ?: JSONObject()))
            "ui.currentScreen" -> completed(action, "screen" to (stateRepository?.snapshotJson()?.optJSONObject("app")?.optString("screen").orEmpty()))
            "ui.availableActions" -> completed(action, "actions" to (stateRepository?.actionsJson() ?: JSONArray()))
            "operations.get" -> {
                val id = args.optString("operationId").trim()
                require(id.isNotBlank()) { "operationId is required" }
                val operation = operationStore.get(id)
                    ?: return error("OPERATION_NOT_FOUND", "Unknown operationId", false, mapOf("operationId" to id))
                completed(action, "operation" to operation.toJson())
            }
            "catalog.search" -> catalogSearch(args)
            "catalog.query" -> catalogQuery(args)
            "people.search" -> peopleSearch(args)
            "media.details" -> mediaDetails(args)
            "media.play" -> startMediaOperation(args, requestId)
            "library.snapshot" -> completed(action, "library" to (stateRepository?.libraryJson() ?: JSONObject()))
            "library.setFavorite" -> setLibraryFlag(args, favorite = true)
            "library.setWatchLater" -> setLibraryFlag(args, favorite = false)
            "library.setMyList" -> setMyList(args)
            "history.clear" -> clearHistory()
            "searchHistory.clear" -> clearSearchHistory()
            "downloads.snapshot" -> completed(action, "downloads" to (stateRepository?.snapshotJson()?.optJSONObject("downloads") ?: JSONObject()))
            "downloads.enqueue" -> enqueueDownload(args)
            "downloads.status" -> downloadStatus(args)
            "downloads.delete" -> deleteDownload(args)
            "downloads.deleteAll" -> deleteAllDownloads()
            "settings.list" -> completed(action, "settings" to (stateRepository?.currentSettingsJson() ?: JSONArray()))
            "settings.set" -> setSetting(args)
            "diagnostics.snapshot" -> completed(action, "diagnostics" to diagnosticsJson())
            "diagnostics.events" -> {
                val limit = args.optInt("limit", 100).coerceIn(1, 1000)
                completed(action, "events" to JSONArray().apply { eventBus?.snapshot(limit)?.forEach { put(it.toJson()) } })
            }
            "player.play" -> {
                requireSession(session)
                runOnMain { session!!.player.play() }
                completed(action, "playing" to true)
            }
            "player.pause" -> {
                requireSession(session)
                runOnMain { session!!.player.pause() }
                completed(action, "playing" to false)
            }
            "player.toggle" -> {
                requireSession(session)
                runOnMain { session!!.togglePlayPause() }
                completed(action)
            }
            "player.stop" -> {
                requireSession(session)
                runOnMain { session!!.stopAndClear() }
                completed(action)
            }
            "player.seek" -> {
                requireSession(session)
                val position = args.optLong("positionMs", -1L)
                require(position >= 0L) { "positionMs must be non-negative" }
                runOnMain { session!!.seekTo(position) }
                completed(action, "positionMs" to position)
            }
            "player.seekRelative" -> {
                requireSession(session)
                val seconds = args.optLong("seconds", 0L)
                runOnMain { session!!.seekTo((session.player.currentPosition + seconds * 1000L).coerceAtLeast(0L)) }
                completed(action, "seconds" to seconds)
            }
            "player.getStreams" -> {
                requireSession(session)
                completed(action, "streams" to streamsJson())
            }
            "player.selectStream" -> selectStream(args, requestId)
            "player.selectQuality" -> selectQuality(args, requestId)
            "player.selectVoice" -> selectVoice(args, requestId)
            "player.nextEpisode" -> startAdjacentEpisode(+1, requestId)
            "player.previousEpisode" -> startAdjacentEpisode(-1, requestId)

            // Presentation-only actions.
            "navigation.home", "navigation.catalog", "navigation.library", "navigation.back" -> {
                val handler = onNavigate ?: return error("UI_NOT_ATTACHED", "Navigation handler is not registered", true)
                runOnMain { handler(action.substringAfter('.')) }
                completed(action)
            }
            "player.openSettings" -> {
                val handler = onOpenPlayerSettings ?: return error("UI_NOT_ATTACHED", "Player settings handler is not registered", true)
                runOnMain { handler() }
                completed(action, "settingsOpen" to true)
            }
            "player.closeSettings" -> {
                val handler = onClosePlayerSettings ?: return error("UI_NOT_ATTACHED", "Player settings handler is not registered", true)
                runOnMain { handler() }
                completed(action, "settingsOpen" to false)
            }
            "player.enterFullscreen" -> {
                val handler = onEnterFullscreen ?: return error("UI_NOT_ATTACHED", "Fullscreen handler is not registered", true)
                runOnMain { handler() }
                completed(action)
            }
            "player.exitFullscreen" -> {
                val handler = onExitFullscreen ?: return error("UI_NOT_ATTACHED", "Fullscreen exit handler is not registered", true)
                runOnMain { handler() }
                completed(action)
            }
            "player.enterPip" -> {
                val handler = onEnterPip ?: return error("UI_NOT_ATTACHED", "PiP handler is not registered", true)
                runOnMain { handler() }
                completed(action)
            }
            else -> error("UNKNOWN_ACTION", "Unknown action: $action", false)
        }
    }

    private fun catalogSearch(args: JSONObject): JSONObject {
        val query = args.optString("query").trim()
        require(query.isNotBlank()) { "query is required" }
        val limit = args.optInt("limit", 20).coerceIn(1, 50)
        val items = runBlocking(Dispatchers.IO) { DemoCatalogRepository.search(query, limit) }
        return completed("catalog.search", "query" to query, "items" to JSONArray().apply { items.forEach { put(mediaSummaryJson(it)) } })
    }

    private fun catalogQuery(args: JSONObject): JSONObject {
        val limit = args.optInt("limit", 40).coerceIn(1, 100)
        val offset = args.optInt("offset", 0).coerceAtLeast(0)
        val sortName = args.optString("sort", "POPULAR").trim().uppercase()
        val sort = runCatching { CatalogSort.valueOf(sortName) }
            .getOrElse { throw IllegalArgumentException("Unknown sort: $sortName") }
        val category = args.optString("category").trim().takeIf { it.isNotBlank() }?.let { raw ->
            runCatching { CatalogCategory.valueOf(raw.uppercase()) }.getOrElse {
                throw IllegalArgumentException("Unknown category: $raw")
            }
        }
        val type = args.optString("type").trim().takeIf { it.isNotBlank() && !it.equals("ANY", true) }?.let { raw ->
            runCatching { ContentType.valueOf(raw.uppercase()) }.getOrElse {
                throw IllegalArgumentException("Unknown content type: $raw")
            }
        }
        val genres = args.optJSONArray("genres")?.let { array ->
            buildSet {
                for (index in 0 until array.length()) {
                    array.optString(index).trim().takeIf { it.isNotBlank() }?.let(::add)
                }
            }
        }.orEmpty()
        val filter = CatalogFilter(
            type = type,
            genres = genres,
            yearFrom = args.optInt("yearFrom", -1).takeIf { it > 0 },
            yearTo = args.optInt("yearTo", -1).takeIf { it > 0 },
            minRating = args.optDouble("minRating", -1.0).takeIf { it >= 0.0 },
            resolution = args.optString("resolution").trim().takeIf { it.isNotBlank() },
            country = args.optString("country").trim().takeIf { it.isNotBlank() },
            durationMode = args.optString("durationMode", "ANY").uppercase(),
            newOnly = args.optBoolean("newOnly", false),
            maxAgeRating = args.optInt("maxAgeRating", -1).takeIf { it >= 0 },
            audioLanguage = args.optString("audioLanguage").trim().takeIf { it.isNotBlank() },
            subtitleLanguage = args.optString("subtitleLanguage").trim().takeIf { it.isNotBlank() },
        )
        require(filter.durationMode in setOf("ANY", "SHORT", "MEDIUM", "LONG")) { "Invalid durationMode" }
        val query = args.optString("query").trim()

        val result = runBlocking(Dispatchers.IO) {
            val totalAvailable = DemoCatalogRepository.getTotalCount().coerceAtLeast(0)
            val fetchLimit = totalAvailable.coerceIn(1, 10_000)
            val all = DemoCatalogRepository.getPaged(
                limit = fetchLimit,
                offset = 0,
                sort = CatalogSort.POPULAR,
                category = null,
                filter = null,
                query = null,
            )
            val searched = if (query.isBlank()) all else searchCatalogLocally(all, query, limit = all.size)
            val categoryFiltered = if (category == null) searched else searched.filter { it.category == category }
            val filtered = filterCatalog(categoryFiltered, filter)
            val sorted = sortCatalog(filtered, sort)
            val page = if (offset >= sorted.size) emptyList() else sorted.drop(offset).take(limit)
            Triple(page, sorted.size, totalAvailable)
        }
        return completed(
            "catalog.query",
            "items" to JSONArray().apply { result.first.forEach { put(mediaSummaryJson(it)) } },
            "total" to result.second,
            "catalogTotal" to result.third,
            "limit" to limit,
            "offset" to offset,
            "sort" to sort.name,
            "category" to category?.name,
            "type" to type?.name,
            "query" to query,
            "filter" to JSONObject().apply {
                put("genres", JSONArray(genres.sorted()))
                put("yearFrom", filter.yearFrom)
                put("yearTo", filter.yearTo)
                put("minRating", filter.minRating)
                put("resolution", filter.resolution)
                put("country", filter.country)
                put("durationMode", filter.durationMode)
                put("newOnly", filter.newOnly)
                put("maxAgeRating", filter.maxAgeRating)
                put("audioLanguage", filter.audioLanguage)
                put("subtitleLanguage", filter.subtitleLanguage)
            },
        )
    }

    private fun peopleSearch(args: JSONObject): JSONObject {
        val query = args.optString("query").trim()
        require(query.isNotBlank()) { "query is required" }
        val limit = args.optInt("limit", 20).coerceIn(1, 50)
        val people = runBlocking(Dispatchers.IO) { DemoCatalogRepository.searchPeople(query, limit) }
        return completed(
            "people.search",
            "query" to query,
            "people" to JSONArray().apply {
                people.forEach { person ->
                    put(JSONObject()
                        .put("name", person.name)
                        .put("photoUrl", person.photoUrl)
                        .put("role", person.role)
                        .put("knownFor", JSONArray(person.knownFor)))
                }
            },
        )
    }

    private fun mediaDetails(args: JSONObject): JSONObject {
        val mediaId = args.optString("mediaId").trim()
        val title = args.optString("title").trim()
        require(mediaId.isNotBlank() || title.isNotBlank()) { "mediaId or title is required" }
        val item = runBlocking(Dispatchers.IO) {
            when {
                mediaId.isNotBlank() -> DemoCatalogRepository.findFullById(mediaId) ?: DemoCatalogRepository.findById(mediaId)
                else -> DemoCatalogRepository.findFullByTitle(title) ?: DemoCatalogRepository.findByTitle(title)
            }
        } ?: return error("MEDIA_NOT_FOUND", "Media not found", false, mapOf("mediaId" to mediaId, "title" to title))
        return completed("media.details", "media" to mediaDetailsJson(item))
    }

    private fun startMediaOperation(args: JSONObject, requestId: String): JSONObject {
        val requested = linkedMapOf<String, Any?>(
            "mediaId" to args.optString("mediaId").takeIf { it.isNotBlank() },
            "title" to args.optString("title").takeIf { it.isNotBlank() },
            "season" to args.optInt("season", -1).takeIf { it > 0 },
            "episode" to args.optInt("episode", -1).takeIf { it > 0 },
            "quality" to args.optString("quality").takeIf { it.isNotBlank() },
            "voice" to args.optString("voice").takeIf { it.isNotBlank() },
            "streamId" to args.optString("streamId").takeIf { it.isNotBlank() },
            "resume" to if (args.has("resume")) args.optBoolean("resume") else true,
            "persist" to if (args.has("persist")) {
                args.optBoolean("persist")
            } else {
                args.optString("quality").isNotBlank() || args.optString("voice").isNotBlank() || args.optString("streamId").isNotBlank()
            },
        )
        require(requested["mediaId"] != null || requested["title"] != null) { "mediaId or title is required" }
        val operationScope = scope ?: return error("BRIDGE_NOT_STARTED", "Movia agent scope is not initialized")
        val started = beginSelectionOperation("media.play", requestId, requested)
        val operation = started.operation
        publishOperation("OPERATION_ACCEPTED", operation)
        operationScope.launch(Dispatchers.IO) {
            markSelectionRunning(started.token)?.let { publishOperation("OPERATION_RUNNING", it) } ?: return@launch
            try {
                val context = appContext ?: throw IllegalStateException("Application context unavailable")
                val prefs = preferences ?: throw IllegalStateException("Preferences unavailable")
                val library = libraryRepository ?: throw IllegalStateException("Library unavailable")
                val mediaId = requested["mediaId"] as String?
                val requestedTitle = requested["title"] as String?
                val content = when {
                    !mediaId.isNullOrBlank() -> DemoCatalogRepository.findFullById(mediaId) ?: DemoCatalogRepository.findById(mediaId)
                    else -> DemoCatalogRepository.findFullByTitle(requestedTitle.orEmpty()) ?: DemoCatalogRepository.findByTitle(requestedTitle.orEmpty())
                } ?: throw IllegalArgumentException("MEDIA_NOT_FOUND")

                val season = requested["season"] as Int?
                val episode = requested["episode"] as Int?
                if ((season == null) != (episode == null)) throw IllegalArgumentException("season and episode must be supplied together")
                val displayTitle = displayTitle(content, season, episode)
                val playbackPrefs = prefs.playbackPreferences.first()
                val titlePrefs = prefs.titlePlaybackPreferences(content.title).first()
                val progress = if (requested["resume"] == true) library.progressByTitle.first()[displayTitle] else null
                val streamId = requested["streamId"] as String?
                val knownStreams = content.streams.filter { it.url.isNotBlank() }
                val exactKnown = streamId?.let { id -> knownStreams.firstOrNull { it.streamId == id } }
                val quality = (requested["quality"] as String?) ?: exactKnown?.quality ?: titlePrefs.quality ?: playbackPrefs.quality
                val voice = (requested["voice"] as String?) ?: exactKnown?.voice ?: titlePrefs.audio ?: playbackPrefs.audio
                val localFile = DownloadScheduler.localFile(context, displayTitle)
                    ?: DownloadScheduler.localFile(context, content.title)
                var session: PlaybackSession? = null
                withContext(Dispatchers.Main) {
                    if (!isCurrentSelection(started.token)) return@withContext
                    session = MoviaPlaybackRegistry.obtain(context)
                    session?.start(
                        mediaId = content.id,
                        title = displayTitle,
                        contentYear = content.year,
                        seasonNumber = season,
                        episodeNumber = episode,
                        mediaType = content.type,
                        sourceUri = localFile?.toURI()?.toString() ?: exactKnown?.url ?: knownStreams.firstOrNull()?.url,
                        startPositionMs = progress?.positionMs ?: 0L,
                        audioTrackId = playbackPrefs.audio,
                        subtitleTrackId = if (playbackPrefs.subtitlesEnabled) "Auto" else null,
                        preferredQuality = quality,
                        preferredVoice = voice,
                        preferredStreamId = streamId,
                        candidateStreams = knownStreams.map { it.url },
                        candidateStreamOptions = knownStreams,
                    )
                }
                val playbackSession = session ?: throw IllegalStateException("Playback session unavailable")
                if (!isCurrentSelection(started.token)) return@launch
                if (!isCurrentPlaybackSession(playbackSession)) {
                    failSelectionIfCurrent(
                        started.token,
                        "SELECTION_STALE",
                        "Active playback session changed while media was starting",
                    )?.let { publishOperation("OPERATION_FAILED", it) }
                    return@launch
                }
                library.addHistory(displayTitle)

                if (!streamId.isNullOrBlank()) {
                    val deadline = System.currentTimeMillis() + 20_000L
                    var exact: StreamOption? = null
                    while (System.currentTimeMillis() < deadline && exact == null) {
                        if (!isCurrentSelection(started.token)) return@launch
                        exact = playbackSession.streamOptions.value.firstOrNull { it.streamId == streamId }
                        if (exact == null) delay(100L)
                    }
                    val exactStream = exact ?: throw IllegalArgumentException("STREAM_NOT_FOUND")
                    withContext(Dispatchers.Main) {
                        if (isCurrentSelection(started.token)) {
                            val selection = playbackSession.state.value.activeStreamSelection
                            val alreadyRequested = selection?.requestedStreamId == exactStream.streamId
                            val alreadyActive = selection?.activeStreamId == exactStream.streamId
                            if (!alreadyRequested && !alreadyActive) {
                                playbackSession.switchToStream(exactStream)
                            }
                        }
                    }
                }
                awaitPlaybackOperation(
                    operation.operationId,
                    playbackSession,
                    content.id,
                    streamId,
                    quality,
                    voice,
                    started.token,
                    persistTitle = content.title.takeIf { requested["persist"] == true },
                )
            } catch (throwable: Throwable) {
                val code = when (throwable.message) {
                    "MEDIA_NOT_FOUND" -> "MEDIA_NOT_FOUND"
                    "STREAM_NOT_FOUND" -> "STREAM_NOT_FOUND"
                    else -> "MEDIA_PLAY_FAILED"
                }
                failSelectionIfCurrent(started.token, code, throwable.message ?: "Media play failed")?.let { failed ->
                    publishOperation("OPERATION_FAILED", failed)
                    eventBus?.publish("COMMAND_FAILED", requestId, mapOf("action" to "media.play", "operationId" to failed.operationId, "code" to code))
                }
            }
        }
        return accepted("media.play", operation.operationId, requested)
    }

    private suspend fun awaitPlaybackOperation(
        operationId: String,
        session: PlaybackSession,
        expectedMediaId: String,
        expectedStreamId: String?,
        expectedQuality: String?,
        expectedVoice: String?,
        token: SelectionOperationToken,
        persistTitle: String? = null,
    ) {
        val deadline = System.currentTimeMillis() + 60_000L
        var previousPositionMs: Long? = null
        var positionMoved = false
        while (System.currentTimeMillis() < deadline) {
            if (!isCurrentSelection(token)) return
            if (!isCurrentPlaybackSession(session)) {
                failSelectionIfCurrent(
                    token,
                    "SELECTION_STALE",
                    "Active playback session changed while selection was in progress",
                )?.let { publishOperation("OPERATION_FAILED", it) }
                return
            }
            val state = session.state.value
            val selection = state.activeStreamSelection
            if (state.status.name == "FAILED" || state.switchState.name == "FAILED") {
                val reason = state.statusMessage ?: selection?.fallbackReason ?: "Playback failed"
                failSelectionIfCurrent(token, "PLAYBACK_FAILED", reason)?.let { publishOperation("OPERATION_FAILED", it) }
                return
            }
            if (state.mediaId.isNotBlank() && state.mediaId != expectedMediaId) {
                failSelectionIfCurrent(
                    token,
                    "SELECTION_STALE",
                    "Active media changed while selection was in progress",
                )?.let { publishOperation("OPERATION_FAILED", it) }
                return
            }
            val ready = state.mediaId == expectedMediaId && state.switchState.name == "READY"
            val selectionMatches = activeSelectionMatches(selection, expectedStreamId, expectedQuality, expectedVoice)
            if (ready && !selectionMatches) {
                val mismatch = buildString {
                    append("Requested playback variant did not become active")
                    expectedQuality?.takeUnless { it.equals("Auto", ignoreCase = true) }?.let {
                        append("; quality=").append(it).append(" active=").append(selection?.activeQuality)
                    }
                    expectedVoice?.takeUnless { it.equals("Auto", ignoreCase = true) }?.let {
                        append("; voice=").append(it).append(" active=").append(selection?.activeVoice)
                    }
                    expectedStreamId?.let { append("; streamId=").append(it).append(" active=").append(selection?.activeStreamId) }
                    selection?.fallbackReason?.let { append("; fallback=").append(it) }
                }
                failSelectionIfCurrent(token, "SELECTION_MISMATCH", mismatch)?.let { publishOperation("OPERATION_FAILED", it) }
                return
            }
            if (ready && selectionMatches) {
                val evidence = runOnMainResult { session.realPlaybackEvidence() }
                if (evidence.first) {
                    val previous = previousPositionMs
                    if (previous != null && evidence.second > previous) positionMoved = true
                }
                previousPositionMs = evidence.second
                if (!evidence.first || !positionMoved) {
                    delay(200L)
                    continue
                }
                val completed = completeSelectionIfCurrent(
                    token = token,
                    session = session,
                    expectedMediaId = expectedMediaId,
                    expectedStreamId = expectedStreamId,
                    expectedQuality = expectedQuality,
                    expectedVoice = expectedVoice,
                    actualPlaybackEvidence = true,
                    persistTitle = persistTitle,
                ) { completedState, completedSelection ->
                    mapOf(
                        "mediaId" to completedState.mediaId,
                        "title" to completedState.displayTitle,
                        "season" to completedState.seasonNumber,
                        "episode" to completedState.episodeNumber,
                        "status" to completedState.status.name,
                        "switchState" to completedState.switchState.name,
                        "requestedStreamId" to completedSelection?.requestedStreamId,
                        "activeStreamId" to completedSelection?.activeStreamId,
                        "quality" to completedSelection?.activeQuality,
                        "voice" to completedSelection?.activeVoice,
                    )
                }
                if (completed != null) {
                    publishOperation("OPERATION_COMPLETED", completed)
                    eventBus?.publish("COMMAND_COMPLETED", completed.requestId, mapOf("action" to completed.action, "operationId" to completed.operationId))
                    return
                }
            }
            delay(200L)
        }
        failSelectionIfCurrent(
            token,
            "PLAYBACK_TIMEOUT",
            "Playback did not reach a stable ready selection in time",
        )?.let {
            publishOperation("OPERATION_FAILED", it)
        }
    }

    private fun selectStream(args: JSONObject, requestId: String): JSONObject {
        val session = MoviaPlaybackRegistry.current
        requireSession(session)
        val playbackSession = session ?: return error("NO_ACTIVE_MEDIA", "No active media", true)
        val id = args.optString("streamId").trim()
        require(id.isNotBlank()) { "streamId is required" }
        val expectedMediaId = playbackSession.state.value.mediaId
        val candidate = playbackSession.streamOptions.value.firstOrNull { it.streamId == id }
            ?: return error("STREAM_NOT_FOUND", "Unknown streamId", true, mapOf("streamId" to id))
        val persist = if (args.has("persist")) args.optBoolean("persist") else true
        return startStreamSwitchOperation("player.selectStream", playbackSession, expectedMediaId, candidate, requestId, persist)
    }

    private fun selectQuality(args: JSONObject, requestId: String): JSONObject {
        val session = MoviaPlaybackRegistry.current
        requireSession(session)
        val playbackSession = session ?: return error("NO_ACTIVE_MEDIA", "No active media", true)
        val quality = args.optString("quality").trim()
        require(quality.isNotBlank()) { "quality is required" }
        val expectedMediaId = playbackSession.state.value.mediaId
        val current = playbackSession.state.value.activeStreamSelection
        val preferredVoice = preferredVoiceForQuality(current)
        val candidate = playbackSession.streamOptions.value.firstOrNull {
            it.quality.equals(quality, ignoreCase = true) &&
                (preferredVoice == null || it.voice.equals(preferredVoice, ignoreCase = true))
        } ?: playbackSession.streamOptions.value.firstOrNull { it.quality.equals(quality, ignoreCase = true) }
            ?: return error("QUALITY_NOT_FOUND", "Quality is not available", true)
        val persist = if (args.has("persist")) args.optBoolean("persist") else true
        return startStreamSwitchOperation("player.selectQuality", playbackSession, expectedMediaId, candidate, requestId, persist)
    }

    private fun selectVoice(args: JSONObject, requestId: String): JSONObject {
        val session = MoviaPlaybackRegistry.current
        requireSession(session)
        val playbackSession = session ?: return error("NO_ACTIVE_MEDIA", "No active media", true)
        val voice = args.optString("voice").trim()
        require(voice.isNotBlank()) { "voice is required" }
        val expectedMediaId = playbackSession.state.value.mediaId
        val current = playbackSession.state.value.activeStreamSelection
        val preferredQuality = preferredQualityForVoice(current)
        val candidate = playbackSession.streamOptions.value.firstOrNull {
            it.voice.equals(voice, ignoreCase = true) &&
                (preferredQuality == null || it.quality.equals(preferredQuality, ignoreCase = true))
            } ?: return error("VOICE_NOT_FOUND", "Voice is not available for current quality", true)
        val persist = if (args.has("persist")) args.optBoolean("persist") else true
        return startStreamSwitchOperation("player.selectVoice", playbackSession, expectedMediaId, candidate, requestId, persist)
    }

    private fun startStreamSwitchOperation(
        action: String,
        session: PlaybackSession,
        expectedMediaId: String,
        candidate: StreamOption,
        requestId: String,
        persist: Boolean,
    ): JSONObject {
        val requested = mapOf(
            "mediaId" to expectedMediaId,
            "requestedStreamId" to candidate.streamId,
            "requestedQuality" to candidate.quality,
            "requestedVoice" to candidate.voice,
            "persist" to persist,
        )
        val started = beginSelectionOperation(action, requestId, requested)
        val operation = started.operation
        publishOperation("OPERATION_ACCEPTED", operation)
        try {
            runOnMain {
                if (isCurrentSelection(started.token)) session.switchToStream(candidate)
            }
        } catch (throwable: Throwable) {
            val message = throwable.message ?: "Stream switch failed"
            failSelectionIfCurrent(started.token, "STREAM_SWITCH_FAILED", message)?.let {
                publishOperation("OPERATION_FAILED", it)
            }
            return error(
                "STREAM_SWITCH_FAILED",
                message,
                true,
                mapOf("operationId" to operation.operationId),
            )
        }
        val operationScope = scope ?: run {
            failSelectionIfCurrent(started.token, "BRIDGE_NOT_STARTED", "Movia agent scope is not initialized")?.let {
                publishOperation("OPERATION_FAILED", it)
            }
            return error("BRIDGE_NOT_STARTED", "Movia agent scope is not initialized", true)
        }
        operationScope.launch {
            markSelectionRunning(started.token)?.let { publishOperation("OPERATION_RUNNING", it) } ?: return@launch
            try {
                val deadline = System.currentTimeMillis() + 45_000L
                var previousPositionMs: Long? = null
                var positionMoved = false
                while (System.currentTimeMillis() < deadline) {
                    if (!isCurrentSelection(started.token)) return@launch
                    if (!isCurrentPlaybackSession(session)) {
                        failSelectionIfCurrent(
                            started.token,
                            "SELECTION_STALE",
                            "Active playback session changed while stream switch was in progress",
                        )?.let { publishOperation("OPERATION_FAILED", it) }
                        return@launch
                    }
                    val state = session.state.value
                    val selection = state.activeStreamSelection
                    if (state.switchState.name == "FAILED" || state.status.name == "FAILED") {
                        failSelectionIfCurrent(
                            started.token,
                            "STREAM_SWITCH_FAILED",
                            state.statusMessage ?: selection?.fallbackReason ?: "Stream switch failed",
                        )?.let {
                            publishOperation("OPERATION_FAILED", it)
                        }
                        return@launch
                    }
                    if (state.mediaId.isNotBlank() && state.mediaId != expectedMediaId) {
                        failSelectionIfCurrent(
                            started.token,
                            "SELECTION_STALE",
                            "Active media changed while stream switch was in progress",
                        )?.let { publishOperation("OPERATION_FAILED", it) }
                        return@launch
                    }
                    if (state.switchState.name == "READY") {
                        val matches = activeSelectionMatches(
                            selection,
                            expectedStreamId = candidate.streamId,
                            expectedQuality = candidate.quality,
                            expectedVoice = candidate.voice,
                        )
                        if (!matches) {
                            failSelectionIfCurrent(
                                started.token,
                                "SELECTION_MISMATCH",
                                "Requested stream selection did not become active",
                            )?.let {
                                publishOperation("OPERATION_FAILED", it)
                            }
                            return@launch
                        }
                        val evidence = runOnMainResult { session.realPlaybackEvidence() }
                        if (evidence.first) {
                            val previous = previousPositionMs
                            if (previous != null && evidence.second > previous) positionMoved = true
                        }
                        previousPositionMs = evidence.second
                        if (!evidence.first || !positionMoved) {
                            delay(150L)
                            continue
                        }
                        val baseTitle = state.displayTitle.substringBefore(" · S").substringBefore(" · E")
                        val completed = completeSelectionIfCurrent(
                            token = started.token,
                            session = session,
                            expectedMediaId = expectedMediaId,
                            expectedStreamId = candidate.streamId,
                            expectedQuality = candidate.quality,
                            expectedVoice = candidate.voice,
                            actualPlaybackEvidence = true,
                            persistTitle = baseTitle.takeIf { persist },
                        ) { completedState, completedSelection ->
                            mapOf(
                                "activeStreamId" to completedSelection?.activeStreamId,
                                "quality" to completedSelection?.activeQuality,
                                "voice" to completedSelection?.activeVoice,
                                "status" to completedState.status.name,
                            )
                        }
                        if (completed != null) {
                            publishOperation("OPERATION_COMPLETED", completed)
                            eventBus?.publish("COMMAND_COMPLETED", requestId, mapOf("action" to action, "operationId" to completed.operationId))
                            return@launch
                        }
                    }
                    delay(150L)
                }
                failSelectionIfCurrent(
                    started.token,
                    "STREAM_SWITCH_TIMEOUT",
                    "Stream switch did not become active in time",
                )?.let {
                    publishOperation("OPERATION_FAILED", it)
                }
            } catch (throwable: Throwable) {
                val message = throwable.message ?: "Stream switch failed"
                failSelectionIfCurrent(started.token, "STREAM_SWITCH_FAILED", message)?.let {
                    publishOperation("OPERATION_FAILED", it)
                }
            }
        }
        return accepted(action, operation.operationId, requested)
    }

    private fun startAdjacentEpisode(delta: Int, requestId: String): JSONObject {
        val state = MoviaPlaybackRegistry.current?.state?.value
            ?: return error("NO_ACTIVE_MEDIA", "No active media", true)
        if (!state.hasMedia) return error("NO_ACTIVE_MEDIA", "No active media", true)
        val content = DemoCatalogRepository.findById(state.mediaId)
            ?: return error("MEDIA_NOT_FOUND", "Current media metadata unavailable", true)
        var season = state.seasonNumber ?: 1
        var episode = state.episodeNumber ?: 1
        val counts = content.seasonEpisodeCounts
        if (delta > 0) {
            val count = counts.getOrNull(season - 1) ?: return error("NO_EPISODE_METADATA", "Episode count unavailable", true)
            if (episode < count) episode += 1
            else if (season < counts.size) { season += 1; episode = 1 }
            else return error("NO_NEXT_EPISODE", "No next episode", false)
        } else {
            if (episode > 1) episode -= 1
            else if (season > 1) { season -= 1; episode = counts.getOrNull(season - 1) ?: 1 }
            else return error("NO_PREVIOUS_EPISODE", "No previous episode", false)
        }
        return startMediaOperation(JSONObject()
            .put("mediaId", content.id)
            .put("season", season)
            .put("episode", episode)
            .put("resume", true), requestId)
    }

    private fun setLibraryFlag(args: JSONObject, favorite: Boolean): JSONObject {
        val library = libraryRepository ?: return error("BRIDGE_NOT_STARTED", "Library unavailable")
        val title = resolveTitle(args) ?: return error("MEDIA_NOT_FOUND", "mediaId or title is required", false)
        require(args.has("enabled")) { "enabled is required" }
        val enabled = args.optBoolean("enabled")
        runBlocking(Dispatchers.IO) {
            if (favorite) library.setFavorite(title, enabled) else library.setWatchLater(title, enabled)
        }
        val action = if (favorite) "library.setFavorite" else "library.setWatchLater"
        return completed(action, "title" to title, "enabled" to enabled)
    }

    private fun setMyList(args: JSONObject): JSONObject {
        val library = libraryRepository ?: return error("BRIDGE_NOT_STARTED", "Library unavailable")
        val title = resolveTitle(args) ?: return error("MEDIA_NOT_FOUND", "mediaId or title is required", false)
        require(args.has("enabled")) { "enabled is required" }
        val enabled = args.optBoolean("enabled")
        runBlocking(Dispatchers.IO) {
            library.setFavorite(title, enabled)
            library.setWatchLater(title, enabled)
        }
        return completed("library.setMyList", "title" to title, "enabled" to enabled)
    }

    private fun clearSearchHistory(): JSONObject {
        val library = libraryRepository ?: return error("BRIDGE_NOT_STARTED", "Library unavailable")
        runBlocking(Dispatchers.IO) { library.clearSearchHistory() }
        return completed("searchHistory.clear")
    }

    private fun enqueueDownload(args: JSONObject): JSONObject {
        val context = appContext ?: return error("BRIDGE_NOT_STARTED", "Application context unavailable")
        val title = resolveTitle(args) ?: return error("MEDIA_NOT_FOUND", "mediaId or title is required", false)
        val wifiOnly = if (args.has("wifiOnly")) {
            args.optBoolean("wifiOnly")
        } else {
            runBlocking(Dispatchers.IO) { preferences?.playbackPreferences?.first()?.wifiOnlyDownloads } ?: true
        }
        DownloadScheduler.enqueue(context, title, wifiOnly)
        eventBus?.publish("DOWNLOAD_ENQUEUED", details = mapOf("title" to title, "wifiOnly" to wifiOnly))
        return completed("downloads.enqueue", "title" to title, "wifiOnly" to wifiOnly)
    }

    private fun downloadStatus(args: JSONObject): JSONObject {
        val context = appContext ?: return error("BRIDGE_NOT_STARTED", "Application context unavailable")
        val title = args.optString("title").trim()
        require(title.isNotBlank()) { "title is required" }
        val status = runBlocking(Dispatchers.IO) { DownloadScheduler.status(context, title) }
        return completed(
            "downloads.status",
            "title" to title,
            "state" to status.state?.name,
            "progressPercent" to status.progressPercent,
            "localFile" to DownloadScheduler.localFile(context, title)?.absolutePath,
        )
    }

    private fun clearHistory(): JSONObject {
        val library = libraryRepository ?: return error("BRIDGE_NOT_STARTED", "Library unavailable")
        runBlocking(Dispatchers.IO) { library.clearHistory() }
        return completed("history.clear")
    }

    private fun deleteDownload(args: JSONObject): JSONObject {
        val context = appContext ?: return error("BRIDGE_NOT_STARTED", "Application context unavailable")
        val library = libraryRepository ?: return error("BRIDGE_NOT_STARTED", "Library unavailable")
        val title = args.optString("title").trim()
        require(title.isNotBlank()) { "title is required" }
        val deleted = DownloadScheduler.delete(context, title)
        runBlocking(Dispatchers.IO) { library.setDownloaded(title, false) }
        return completed("downloads.delete", "title" to title, "fileDeleted" to deleted)
    }

    private fun deleteAllDownloads(): JSONObject {
        val context = appContext ?: return error("BRIDGE_NOT_STARTED", "Application context unavailable")
        val library = libraryRepository ?: return error("BRIDGE_NOT_STARTED", "Library unavailable")
        val deleted = DownloadScheduler.deleteAll(context)
        runBlocking(Dispatchers.IO) { library.clearDownloads() }
        return completed("downloads.deleteAll", "filesDeleted" to deleted)
    }

    private fun setSetting(args: JSONObject): JSONObject {
        val key = args.optString("key").trim()
        require(key.isNotBlank()) { "key is required" }
        require(args.has("value")) { "value is required" }
        val prefs = preferences ?: return error("BRIDGE_NOT_STARTED", "Preferences unavailable")
        runBlocking(Dispatchers.IO) {
            when (key) {
                "appearance.themeMode" -> {
                    val value = args.optString("value").uppercase()
                    require(value in setOf("DARK", "LIGHT", "SYSTEM")) { "Invalid theme mode" }
                    prefs.setThemeMode(value)
                }
                "accessibility.highContrast" -> prefs.setHighContrast(args.optBoolean("value"))
                "player.showSeekButtons" -> prefs.setPersistentSeekButtons(args.optBoolean("value"))
                "notifications.enabled" -> prefs.setNotificationsEnabled(args.optBoolean("value"))
                "player.audio" -> prefs.setAudio(args.optString("value"))
                "player.quality" -> prefs.setQuality(args.optString("value"))
                "player.subtitlesEnabled" -> prefs.setSubtitlesEnabled(args.optBoolean("value"))
                "player.autoNext" -> prefs.setAutoNextEnabled(args.optBoolean("value"))
                "downloads.wifiOnly" -> prefs.setWifiOnlyDownloads(args.optBoolean("value"))
                else -> throw IllegalArgumentException("Unknown setting: $key")
            }
        }
        eventBus?.publish("SETTING_CHANGED", details = mapOf("key" to key, "value" to args.opt("value")))
        return completed("settings.set", "key" to key, "value" to args.opt("value"))
    }

    private fun persistVariant(title: String, quality: String?, voice: String?) {
        val prefs = preferences ?: return
        if (title.isBlank()) return
        runBlocking(Dispatchers.IO) {
            quality?.takeIf { it.isNotBlank() && !it.equals("Auto", true) }?.let { prefs.setTitleQuality(title, it) }
            voice?.takeIf { it.isNotBlank() && !it.equals("Auto", true) }?.let { prefs.setTitleAudio(title, it) }
        }
        eventBus?.publish(
            "PLAYBACK_VARIANT_PERSISTED",
            details = mapOf("title" to title, "quality" to quality, "voice" to voice),
        )
    }

    private fun resolveTitle(args: JSONObject): String? {
        val title = args.optString("title").trim()
        if (title.isNotBlank()) return title
        val id = args.optString("mediaId").trim()
        if (id.isBlank()) return null
        return DemoCatalogRepository.findById(id)?.title ?: DemoCatalogRepository.findFullById(id)?.title
    }

    private fun displayTitle(content: MediaContent, season: Int?, episode: Int?): String =
        if (season != null && episode != null) {
            "${content.title} · S${season.toString().padStart(2, '0')}E${episode.toString().padStart(2, '0')} · Эпизод $episode"
        } else {
            content.title
        }

    private fun mediaSummaryJson(item: MediaContent): JSONObject = JSONObject().apply {
        put("mediaId", item.id)
        put("title", item.title)
        put("type", item.type.name)
        put("category", item.category.name)
        put("year", item.year)
        put("rating", item.rating)
        put("genres", JSONArray(item.genres.sorted()))
        put("seasonsCount", item.seasonsCount)
        put("episodesCount", item.episodesCount)
        put("seasonEpisodeCounts", JSONArray(item.seasonEpisodeCounts))
    }

    private fun mediaDetailsJson(item: MediaContent): JSONObject = mediaSummaryJson(item).apply {
        put("originalTitle", item.originalTitle)
        put("synopsis", item.synopsis)
        put("director", item.director)
        put("durationMinutes", item.durationMinutes)
        put("ageRating", item.ageRating)
        put("country", item.country)
        put("quality", item.quality)
        put("isNew", item.isNew)
        put("popularity", item.popularity)
        put("voteCount", item.voteCount)
        put("imdbRating", item.imdbRating)
        put("audioLanguages", JSONArray(item.audioLanguages.sorted()))
        put("subtitleLanguages", JSONArray(item.subtitleLanguages.sorted()))
        put("relatedContentIds", JSONArray(item.relatedContentIds))
        put("sequelPrequelIds", JSONArray(item.sequelPrequelIds))
        put("posterUrl", item.posterUrl)
        put("backdropUrl", item.backdropUrl)
        put("sourceUrl", item.sourceUrl)
        put("licenseName", item.licenseName)
        put("licenseUrl", item.licenseUrl)
        put("cast", JSONArray().apply {
            item.cast.forEach { person ->
                put(JSONObject()
                    .put("name", person.name)
                    .put("photoUrl", person.photoUrl)
                    .put("role", person.role)
                    .put("knownFor", JSONArray(person.knownFor)))
            }
        })
        put("streams", JSONArray().apply { item.streams.forEach { put(streamJson(it, null, null)) } })
    }

    private fun streamJson(stream: StreamOption, requestedId: String?, activeId: String?): JSONObject = JSONObject().apply {
        put("streamId", stream.streamId)
        put("voice", stream.voice)
        put("translation", stream.voice)
        put("language", stream.language)
        put("quality", stream.quality)
        put("resolution", stream.resolution)
        put("source", stream.source)
        put("sourceId", stream.sourceId)
        put("providerId", stream.providerId)
        put("providerContentId", stream.providerContentId)
        put("transport", stream.transport)
        put("transportMetadata", safeMetadataJson(stream.transportMetadata))
        put("seeders", stream.seeders)
        put("infoHash", stream.infoHash)
        put("fileIndex", stream.fileIndex)
        put("filePath", stream.filePath)
        put("season", stream.seasonNumber)
        put("episode", stream.episodeNumber)
        put("mimeType", stream.mimeType)
        put("codec", stream.codec)
        put("unavailableQuality", stream.unavailableQuality)
        put("internalSubtitles", stream.hasInternalSubtitles)
        put("subtitleList", JSONArray().apply {
            stream.subtitles.forEach { subtitle ->
                put(JSONObject()
                    .put("url", subtitle.url)
                    .put("language", subtitle.language)
                    .put("label", subtitle.label)
                    .put("mimeType", subtitle.mimeType))
            }
        })
        put("userAgentPresent", !stream.userAgent.isNullOrBlank())
        put("headersPresent", stream.headers.isNotEmpty())
        put("headerNames", JSONArray(stream.headers.keys.map(String::lowercase).distinct().sorted()))
        put("downloadUrlPresent", !stream.downloadUrl.isNullOrBlank())
        put("downloadHeadersPresent", stream.downloadHeaders.isNotEmpty())
        put("downloadHeaderNames", JSONArray(stream.downloadHeaders.keys.map(String::lowercase).distinct().sorted()))
        put("skipIntervals", JSONArray().apply {
            stream.skipIntervals.forEach { interval ->
                put(JSONObject().put("startMs", interval.startMs).put("endMs", interval.endMs))
            }
        })
        put("videoTrackIndex", stream.videoTrackIndex)
        put("audioTrackIndex", stream.audioTrackIndex)
        put("advertisementPresent", stream.advertisement != null)
        put("advertisement", stream.advertisement?.let { ad ->
            JSONObject()
                .put("metadata", safeMetadataJson(ad.metadata))
                .put("rawPresent", !ad.raw.isNullOrBlank())
        })
        put("reloadSupported", stream.reloadSupported)
        put("reloadDataPresent", !stream.reloadData.isNullOrBlank())
        put("durationMs", stream.durationMs)
        put("sizeBytes", stream.sizeBytes)
        put("catalogMediaId", stream.catalogMediaId)
        put("canonicalTitle", stream.canonicalTitle)
        put("canonicalYear", stream.canonicalYear)
        put("canonicalMediaType", stream.canonicalMediaType?.name)
        put("healthScore", stream.healthScore)
        put("startupLatencyMs", stream.startupLatencyMs)
        put("recentFailureCount", stream.recentFailureCount)
        put("providerReliability", stream.providerReliability)
        put("requested", stream.streamId == requestedId)
        put("active", stream.streamId == activeId)
    }

    private fun safeMetadataJson(values: Map<String, String>): JSONObject = JSONObject().apply {
        values.toSortedMap().forEach { (key, value) ->
            val normalized = key.replace(Regex("[^A-Za-z0-9]"), "").lowercase()
            if (normalized in setOf("authorization", "cookie", "token", "accesstoken", "password", "secret", "signature", "key", "privatekey", "apikey")) return@forEach
            if (value.isNotBlank() && value.length <= 2048 && !value.contains("\r") && !value.contains("\n")) {
                put(key, value)
            }
        }
    }

    private fun publishOperation(event: String, operation: AgentOperation) {
        eventBus?.publish(event, operation.requestId, mapOf(
            "operationId" to operation.operationId,
            "action" to operation.action,
            "status" to operation.status.name,
            "errorCode" to operation.errorCode,
        ))
    }

    private suspend fun monitorPlayback() {
        var previousState: String? = null
        var previousSelection: String? = null
        while (kotlinx.coroutines.currentCoroutineContext().isActive) {
            val session = MoviaPlaybackRegistry.current
            val snapshot = session?.state?.value
            val stateKey = snapshot?.let {
                it.status.name + ":" + it.switchState.name + ":" + it.isPlaying + ":" +
                    (it.currentPositionMs / 1000L) + ":" + (it.bufferedPositionMs / 1000L)
            }
            val selection = snapshot?.activeStreamSelection
            val selectionKey = selection?.let {
                (it.requestedStreamId ?: "") + ":" + (it.activeStreamId ?: "") + ":" +
                    (it.requestedQuality ?: "") + ":" + (it.requestedVoice ?: "") + ":" +
                    (it.activeQuality ?: "") + ":" + (it.activeVoice ?: "") + ":" + (it.fallbackReason ?: "")
            }
            if (stateKey != null && stateKey != previousState) {
                eventBus?.publish(
                    "PLAYBACK_STATE_CHANGED",
                    details = mapOf(
                        "status" to snapshot?.status?.name,
                        "switchState" to snapshot?.switchState?.name,
                        "playing" to snapshot?.isPlaying,
                        "positionMs" to snapshot?.currentPositionMs,
                        "durationMs" to snapshot?.totalDurationMs,
                        "bufferedMs" to snapshot?.bufferedPositionMs,
                    ),
                )
                previousState = stateKey
            }
            if (selectionKey != null && selectionKey != previousSelection) {
                eventBus?.publish(
                    if (selection?.activeStreamId != null) "STREAM_SELECTION_CHANGED" else "STREAM_SELECTION_REQUESTED",
                    details = mapOf(
                        "requestedStreamId" to selection?.requestedStreamId,
                        "activeStreamId" to selection?.activeStreamId,
                        "requestedQuality" to selection?.requestedQuality,
                        "requestedVoice" to selection?.requestedVoice,
                        "activeQuality" to selection?.activeQuality,
                        "activeVoice" to selection?.activeVoice,
                        "fallbackReason" to selection?.fallbackReason,
                    ),
                )
                previousSelection = selectionKey
            }
            delay(250L)
        }
    }

    private fun requireSession(session: PlaybackSession?) {
        require(session?.state?.value?.hasMedia == true) { "No active media" }
    }

    private fun isCurrentPlaybackSession(session: PlaybackSession): Boolean =
        MoviaPlaybackRegistry.current === session

    private fun runOnMain(block: () -> Unit): Boolean {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
            return true
        }
        val latch = CountDownLatch(1)
        var error: Throwable? = null
        mainHandler.post {
            try {
                block()
            } catch (throwable: Throwable) {
                error = throwable
            } finally {
                latch.countDown()
            }
        }
        check(latch.await(2, TimeUnit.SECONDS)) { "Main thread dispatch timed out" }
        error?.let { throw it }
        return true
    }

    private fun <T> runOnMainResult(block: () -> T): T {
        if (Looper.myLooper() == Looper.getMainLooper()) return block()
        val latch = CountDownLatch(1)
        var result: T? = null
        var error: Throwable? = null
        mainHandler.post {
            try {
                result = block()
            } catch (throwable: Throwable) {
                error = throwable
            } finally {
                latch.countDown()
            }
        }
        check(latch.await(2, TimeUnit.SECONDS)) { "Main thread dispatch timed out" }
        error?.let { throw it }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    private fun completed(action: String, vararg values: Pair<String, Any?>): JSONObject =
        JSONObject().put("status", "completed").put("action", action).apply {
            values.forEach { (key, value) -> put(key, value ?: JSONObject.NULL) }
        }

    private fun accepted(
        action: String,
        operationId: String,
        values: Map<String, Any?> = emptyMap(),
    ): JSONObject = JSONObject().put("status", "accepted").put("action", action).apply {
        put("operationId", operationId)
        values.forEach { (key, value) -> put(key, value ?: JSONObject.NULL) }
    }

    private fun error(
        code: String,
        message: String,
        retryable: Boolean = false,
        context: Map<String, Any?> = emptyMap(),
    ): JSONObject = JSONObject().put("status", "failed")
        .put("code", code)
        .put("message", message)
        .put("retryable", retryable)
        .put("context", JSONObject().apply {
            context.forEach { (key, value) -> put(key, value ?: JSONObject.NULL) }
        })
}

internal fun activeSelectionMatches(
    selection: ActiveStreamSelection?,
    expectedStreamId: String? = null,
    expectedQuality: String? = null,
    expectedVoice: String? = null,
): Boolean {
    val streamId = expectedStreamId?.trim()?.takeIf { it.isNotBlank() }
    val quality = expectedQuality?.trim()?.takeIf { it.isNotBlank() && !it.equals("Auto", ignoreCase = true) }
    val voice = expectedVoice?.trim()?.takeIf { it.isNotBlank() && !it.equals("Auto", ignoreCase = true) }
    if (streamId == null && quality == null && voice == null) return true
    val active = selection ?: return false
    return (streamId == null || active.activeStreamId == streamId) &&
        (quality == null || active.activeQuality?.equals(quality, ignoreCase = true) == true) &&
        (voice == null || active.activeVoice?.equals(voice, ignoreCase = true) == true)
}

internal fun preferredQualityForVoice(selection: ActiveStreamSelection?): String? =
    concreteSelectionValue(selection?.activeQuality) ?: concreteSelectionValue(selection?.requestedQuality)

internal fun preferredVoiceForQuality(selection: ActiveStreamSelection?): String? =
    concreteSelectionValue(selection?.activeVoice) ?: concreteSelectionValue(selection?.requestedVoice)

private fun concreteSelectionValue(value: String?): String? = value
    ?.trim()
    ?.takeIf { it.isNotBlank() && !it.equals("Auto", ignoreCase = true) }
