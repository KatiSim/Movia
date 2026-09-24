package app.movia.android.agent

import java.util.LinkedHashMap
import java.util.UUID
import org.json.JSONObject

enum class AgentOperationStatus {
    ACCEPTED,
    RUNNING,
    COMPLETED,
    FAILED,
}

data class AgentOperation(
    val operationId: String,
    val requestId: String,
    val action: String,
    val createdAt: Long,
    val updatedAt: Long,
    val status: AgentOperationStatus,
    val requested: Map<String, Any?> = emptyMap(),
    val result: Map<String, Any?> = emptyMap(),
    val errorCode: String? = null,
    val errorMessage: String? = null,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("operationId", operationId)
        put("requestId", requestId)
        put("action", action)
        put("createdAt", createdAt)
        put("updatedAt", updatedAt)
        put("status", status.name)
        put("requested", jsonObject(requested))
        put("result", jsonObject(result))
        errorCode?.let { put("errorCode", it) }
        errorMessage?.let { put("errorMessage", it) }
    }

    private fun jsonObject(values: Map<String, Any?>): JSONObject = JSONObject().apply {
        values.forEach { (key, value) -> put(key, value ?: JSONObject.NULL) }
    }
}

class AgentOperationStore(
    private val capacity: Int = 256,
) {
    private val lock = Any()
    private val operations = LinkedHashMap<String, AgentOperation>()

    fun create(
        action: String,
        requestId: String,
        requested: Map<String, Any?> = emptyMap(),
        operationId: String = "operation-" + UUID.randomUUID(),
    ): AgentOperation = synchronized(lock) {
        val now = System.currentTimeMillis()
        val item = AgentOperation(
            operationId = operationId,
            requestId = requestId,
            action = action,
            createdAt = now,
            updatedAt = now,
            status = AgentOperationStatus.ACCEPTED,
            requested = requested,
        )
        operations[operationId] = item
        trimLocked()
        item
    }

    fun running(operationId: String): AgentOperation? = update(operationId) { current ->
        current.copy(status = AgentOperationStatus.RUNNING, updatedAt = System.currentTimeMillis())
    }

    fun complete(operationId: String, result: Map<String, Any?> = emptyMap()): AgentOperation? =
        update(operationId) { current ->
            current.copy(
                status = AgentOperationStatus.COMPLETED,
                updatedAt = System.currentTimeMillis(),
                result = result,
                errorCode = null,
                errorMessage = null,
            )
        }

    fun fail(operationId: String, code: String, message: String): AgentOperation? =
        update(operationId) { current ->
            current.copy(
                status = AgentOperationStatus.FAILED,
                updatedAt = System.currentTimeMillis(),
                errorCode = code,
                errorMessage = message,
            )
        }

    fun get(operationId: String): AgentOperation? = synchronized(lock) { operations[operationId] }

    fun findByIdempotency(operationId: String): AgentOperation? = get(operationId)

    private fun update(operationId: String, transform: (AgentOperation) -> AgentOperation): AgentOperation? =
        synchronized(lock) {
            val current = operations[operationId] ?: return@synchronized null
            transform(current).also { operations[operationId] = it }
        }

    private fun trimLocked() {
        while (operations.size > capacity) {
            val oldest = operations.entries.firstOrNull()?.key ?: return
            operations.remove(oldest)
        }
    }
}
