package app.movia.android.agent

import org.json.JSONArray
import org.json.JSONObject

object AgentActionRegistry {
    private fun objectSchema(
        properties: Map<String, JSONObject> = emptyMap(),
        required: List<String> = emptyList(),
        additionalProperties: Boolean = false,
    ): JSONObject = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply {
            properties.forEach { (name, schema) -> put(name, schema) }
        })
        put("required", JSONArray(required))
        put("additionalProperties", additionalProperties)
    }

    private fun stringSchema(enumValues: List<String> = emptyList()): JSONObject =
        JSONObject().put("type", "string").apply {
            if (enumValues.isNotEmpty()) put("enum", JSONArray(enumValues))
        }

    private fun booleanSchema(): JSONObject = JSONObject().put("type", "boolean")

    private fun integerSchema(minimum: Long? = null, maximum: Long? = null): JSONObject =
        JSONObject().put("type", "integer").apply {
            minimum?.let { put("minimum", it) }
            maximum?.let { put("maximum", it) }
        }

    private fun numberSchema(minimum: Double? = null, maximum: Double? = null): JSONObject =
        JSONObject().put("type", "number").apply {
            minimum?.let { put("minimum", it) }
            maximum?.let { put("maximum", it) }
        }

    private fun stringArraySchema(): JSONObject = JSONObject()
        .put("type", "array")
        .put("items", stringSchema())

    val definitions: List<AgentActionDefinition> = listOf(
        AgentActionDefinition("app.snapshot", AgentSafety.READ, "Read the complete hot application snapshot"),
        AgentActionDefinition("app.health", AgentSafety.READ, "Read local control-plane health"),
        AgentActionDefinition("app.capabilities", AgentSafety.READ, "Read agent capability discovery"),
        AgentActionDefinition("ui.tree", AgentSafety.READ, "Read the current logical UI tree"),
        AgentActionDefinition("ui.controls", AgentSafety.READ, "Read the stable logical control manifest"),
        AgentActionDefinition("ui.currentScreen", AgentSafety.READ, "Read the current logical screen"),
        AgentActionDefinition("ui.availableActions", AgentSafety.READ, "Read actions available in the current state"),
        AgentActionDefinition(
            "operations.get",
            AgentSafety.READ,
            "Read one asynchronous operation state",
            objectSchema(mapOf("operationId" to stringSchema()), required = listOf("operationId")),
        ),
        AgentActionDefinition(
            "catalog.search",
            AgentSafety.READ,
            "Search Movia catalog without opening UI",
            objectSchema(
                mapOf(
                    "query" to stringSchema(),
                    "limit" to integerSchema(1, 50),
                ),
                required = listOf("query"),
            ),
        ),
        AgentActionDefinition(
            "catalog.query",
            AgentSafety.READ,
            "Query Movia catalog with machine-readable filters, sorting and pagination",
            objectSchema(
                mapOf(
                    "query" to stringSchema(),
                    "limit" to integerSchema(1, 100),
                    "offset" to integerSchema(0),
                    "sort" to stringSchema(listOf("POPULAR", "RATING", "NEWEST", "OLDEST", "CATEGORY", "TITLE")),
                    "category" to stringSchema(listOf("MOVIES", "TV_SERIES", "LIMITED_SERIES", "ANIMATION", "ANIME", "DRAMAS_ASIAN", "DOCUMENTARIES", "THEATER_MUSICALS", "STANDUP", "INTERACTIVE")),
                    "type" to stringSchema(listOf("ANY", "MOVIE", "SERIES", "TV")),
                    "genres" to stringArraySchema(),
                    "yearFrom" to integerSchema(1880, 2200),
                    "yearTo" to integerSchema(1880, 2200),
                    "minRating" to numberSchema(0.0, 10.0),
                    "resolution" to stringSchema(),
                    "country" to stringSchema(),
                    "durationMode" to stringSchema(listOf("ANY", "SHORT", "MEDIUM", "LONG")),
                    "newOnly" to booleanSchema(),
                    "maxAgeRating" to integerSchema(0, 30),
                    "audioLanguage" to stringSchema(),
                    "subtitleLanguage" to stringSchema(),
                ),
            ),
        ),
        AgentActionDefinition(
            "people.search",
            AgentSafety.READ,
            "Search people in the Movia catalog without opening UI",
            objectSchema(
                mapOf("query" to stringSchema(), "limit" to integerSchema(1, 50)),
                required = listOf("query"),
            ),
        ),
        AgentActionDefinition(
            "media.details",
            AgentSafety.READ,
            "Read media details by stable mediaId or title",
            objectSchema(
                mapOf(
                    "mediaId" to stringSchema(),
                    "title" to stringSchema(),
                ),
            ),
        ),
        AgentActionDefinition(
            "media.play",
            AgentSafety.SAFE_WRITE,
            "Start media directly through PlaybackSession without opening Activity",
            objectSchema(
                mapOf(
                    "mediaId" to stringSchema(),
                    "title" to stringSchema(),
                    "season" to integerSchema(1, 999),
                    "episode" to integerSchema(1, 9999),
                    "quality" to stringSchema(),
                    "voice" to stringSchema(),
                    "streamId" to stringSchema(),
                    "resume" to booleanSchema(),
                    "persist" to booleanSchema(),
                ),
            ),
        ),
        AgentActionDefinition("player.play", AgentSafety.SAFE_WRITE, "Start active playback"),
        AgentActionDefinition("player.pause", AgentSafety.SAFE_WRITE, "Pause active playback"),
        AgentActionDefinition("player.toggle", AgentSafety.SAFE_WRITE, "Toggle active playback"),
        AgentActionDefinition("player.stop", AgentSafety.SAFE_WRITE, "Stop and clear active playback"),
        AgentActionDefinition(
            "player.seek",
            AgentSafety.SAFE_WRITE,
            "Seek to an absolute playback position",
            objectSchema(mapOf("positionMs" to integerSchema(0)), required = listOf("positionMs")),
        ),
        AgentActionDefinition(
            "player.seekRelative",
            AgentSafety.SAFE_WRITE,
            "Seek relative to current position",
            objectSchema(mapOf("seconds" to integerSchema()), required = listOf("seconds")),
        ),
        AgentActionDefinition("player.getStreams", AgentSafety.READ, "List stable stream options"),
        AgentActionDefinition(
            "player.selectStream",
            AgentSafety.SAFE_WRITE,
            "Select one stable stream ID",
            objectSchema(mapOf("streamId" to stringSchema(), "persist" to booleanSchema()), required = listOf("streamId")),
        ),
        AgentActionDefinition(
            "player.selectQuality",
            AgentSafety.SAFE_WRITE,
            "Select a stream quality",
            objectSchema(mapOf("quality" to stringSchema(), "persist" to booleanSchema()), required = listOf("quality")),
        ),
        AgentActionDefinition(
            "player.selectVoice",
            AgentSafety.SAFE_WRITE,
            "Select a stream voice for the requested quality",
            objectSchema(mapOf("voice" to stringSchema(), "persist" to booleanSchema()), required = listOf("voice")),
        ),
        AgentActionDefinition("player.nextEpisode", AgentSafety.SAFE_WRITE, "Play the next episode directly"),
        AgentActionDefinition("player.previousEpisode", AgentSafety.SAFE_WRITE, "Play the previous episode directly"),
        AgentActionDefinition("library.snapshot", AgentSafety.READ, "Read favorites, watch-later, history and downloads"),
        AgentActionDefinition(
            "library.setFavorite",
            AgentSafety.SAFE_WRITE,
            "Set favorite state by mediaId or title",
            objectSchema(
                mapOf("mediaId" to stringSchema(), "title" to stringSchema(), "enabled" to booleanSchema()),
                required = listOf("enabled"),
            ),
        ),
        AgentActionDefinition(
            "library.setWatchLater",
            AgentSafety.SAFE_WRITE,
            "Set watch-later state by mediaId or title",
            objectSchema(
                mapOf("mediaId" to stringSchema(), "title" to stringSchema(), "enabled" to booleanSchema()),
                required = listOf("enabled"),
            ),
        ),
        AgentActionDefinition(
            "library.setMyList",
            AgentSafety.SAFE_WRITE,
            "Set the unified My List state used by the visible Details UI",
            objectSchema(
                mapOf("mediaId" to stringSchema(), "title" to stringSchema(), "enabled" to booleanSchema()),
                required = listOf("enabled"),
            ),
        ),
        AgentActionDefinition("searchHistory.clear", AgentSafety.DESTRUCTIVE, "Clear recent catalog search history"),
        AgentActionDefinition("history.clear", AgentSafety.DESTRUCTIVE, "Clear playback history"),
        AgentActionDefinition("downloads.snapshot", AgentSafety.READ, "Read downloaded-title state"),
        AgentActionDefinition(
            "downloads.enqueue",
            AgentSafety.SAFE_WRITE,
            "Queue one offline download using Movia's WorkManager pipeline",
            objectSchema(
                mapOf(
                    "mediaId" to stringSchema(),
                    "title" to stringSchema(),
                    "wifiOnly" to booleanSchema(),
                ),
            ),
        ),
        AgentActionDefinition(
            "downloads.status",
            AgentSafety.READ,
            "Read WorkManager state and progress for one offline download",
            objectSchema(mapOf("title" to stringSchema()), required = listOf("title")),
        ),
        AgentActionDefinition(
            "downloads.delete",
            AgentSafety.DESTRUCTIVE,
            "Delete one offline download by title",
            objectSchema(mapOf("title" to stringSchema()), required = listOf("title")),
        ),
        AgentActionDefinition("downloads.deleteAll", AgentSafety.DESTRUCTIVE, "Delete all offline downloads"),
        AgentActionDefinition("settings.list", AgentSafety.READ, "List machine-keyed settings and metadata"),
        AgentActionDefinition(
            "settings.set",
            AgentSafety.SAFE_WRITE,
            "Set one machine-keyed setting",
            objectSchema(
                mapOf(
                    "key" to stringSchema(),
                    "value" to JSONObject(),
                ),
                required = listOf("key", "value"),
                additionalProperties = false,
            ),
        ),
        AgentActionDefinition("diagnostics.snapshot", AgentSafety.READ, "Read player and bridge diagnostics"),
        AgentActionDefinition(
            "diagnostics.events",
            AgentSafety.READ,
            "Read the event ring buffer",
            objectSchema(mapOf("limit" to integerSchema(1, 1000))),
        ),

        // Presentation-only actions. Domain work should not depend on these.
        AgentActionDefinition("navigation.home", AgentSafety.SAFE_WRITE, "Navigate visible UI to Home", requiresUi = true),
        AgentActionDefinition("navigation.catalog", AgentSafety.SAFE_WRITE, "Navigate visible UI to Catalog", requiresUi = true),
        AgentActionDefinition("navigation.library", AgentSafety.SAFE_WRITE, "Navigate visible UI to Library", requiresUi = true),
        AgentActionDefinition("navigation.back", AgentSafety.SAFE_WRITE, "Navigate visible UI back", requiresUi = true),
        AgentActionDefinition("player.openSettings", AgentSafety.SAFE_WRITE, "Open visible player settings", requiresUi = true),
        AgentActionDefinition("player.closeSettings", AgentSafety.SAFE_WRITE, "Close visible player settings", requiresUi = true),
        AgentActionDefinition("player.enterFullscreen", AgentSafety.SYSTEM, "Enter visible fullscreen player", requiresUi = true),
        AgentActionDefinition("player.exitFullscreen", AgentSafety.SYSTEM, "Exit visible fullscreen player", requiresUi = true),
        AgentActionDefinition("player.enterPip", AgentSafety.SYSTEM, "Enter Android picture-in-picture", requiresUi = true),
    )

    private val byId = definitions.associateBy { it.id }

    fun find(id: String): AgentActionDefinition? = byId[id]
}
