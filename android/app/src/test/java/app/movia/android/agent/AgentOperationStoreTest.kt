package app.movia.android.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class AgentOperationStoreTest {
    @Test
    fun operationLifecycleIsDeterministic() {
        val store = AgentOperationStore(capacity = 4)
        val created = store.create(
            action = "media.play",
            requestId = "req-1",
            requested = mapOf("mediaId" to "123", "quality" to "720p"),
            operationId = "op-1",
        )

        assertEquals(AgentOperationStatus.ACCEPTED, created.status)
        assertEquals("123", created.requested["mediaId"])
        assertEquals(AgentOperationStatus.RUNNING, store.running("op-1")?.status)

        val completed = store.complete(
            "op-1",
            mapOf("activeStreamId" to "stream-1", "voice" to "Кубик в Кубе"),
        )
        assertNotNull(completed)
        assertEquals(AgentOperationStatus.COMPLETED, completed?.status)
        assertEquals("stream-1", completed?.result?.get("activeStreamId"))
        assertNull(completed?.errorCode)
    }

    @Test
    fun failurePreservesMachineReadableError() {
        val store = AgentOperationStore()
        store.create("player.selectStream", "req-2", operationId = "op-2")
        val failed = store.fail("op-2", "STREAM_SWITCH_FAILED", "mirror exhausted")

        assertEquals(AgentOperationStatus.FAILED, failed?.status)
        assertEquals("STREAM_SWITCH_FAILED", failed?.errorCode)
        assertEquals("mirror exhausted", failed?.errorMessage)
    }

    @Test
    fun boundedStoreEvictsOldestOperation() {
        val store = AgentOperationStore(capacity = 2)
        store.create("a", "r1", operationId = "op-1")
        store.create("b", "r2", operationId = "op-2")
        store.create("c", "r3", operationId = "op-3")

        assertNull(store.get("op-1"))
        assertNotNull(store.get("op-2"))
        assertNotNull(store.get("op-3"))
    }
}
