package app.movia.android.agent

import org.json.JSONArray
import org.json.JSONObject

const val MOVIA_AGENT_SCHEMA_VERSION = 2
const val MOVIA_AGENT_PORT = 8899

enum class AgentSafety {
    READ,
    SAFE_WRITE,
    DESTRUCTIVE,
    SYSTEM,
}

data class AgentEvent(
    val event: String,
    val timestamp: Long = System.currentTimeMillis(),
    val requestId: String? = null,
    val details: Map<String, Any?> = emptyMap(),
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("event", event)
        put("timestamp", timestamp)
        putOpt("requestId", requestId)
        put("details", JSONObject().apply {
            details.forEach { (key, value) -> put(key, value ?: JSONObject.NULL) }
        })
    }
}

data class AgentActionDefinition(
    val id: String,
    val safety: AgentSafety,
    val description: String,
    val schema: JSONObject = JSONObject().put("type", "object").put("properties", JSONObject()),
    val requiresUi: Boolean = false,
)

fun JSONArray.toJsonList(): List<JSONObject> = buildList {
    for (index in 0 until length()) {
        optJSONObject(index)?.let(::add)
    }
}
