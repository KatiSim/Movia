package app.movia.android.agent

import java.util.ArrayDeque
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

class AgentEventBus(
    private val capacity: Int = 1000,
) {
    private val lock = Any()
    private val ring = ArrayDeque<AgentEvent>()
    private val _events = MutableSharedFlow<AgentEvent>(extraBufferCapacity = capacity)
    val events: SharedFlow<AgentEvent> = _events.asSharedFlow()

    fun publish(
        event: String,
        requestId: String? = null,
        details: Map<String, Any?> = emptyMap(),
    ): AgentEvent {
        val item = AgentEvent(event, requestId = requestId, details = details)
        synchronized(lock) {
            if (ring.size >= capacity) ring.removeFirst()
            ring.addLast(item)
        }
        _events.tryEmit(item)
        return item
    }

    fun snapshot(limit: Int = 100): List<AgentEvent> = synchronized(lock) {
        ring.toList().takeLast(limit.coerceIn(1, capacity))
    }
}
