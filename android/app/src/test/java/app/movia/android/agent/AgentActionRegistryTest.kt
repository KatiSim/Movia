package app.movia.android.agent

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentActionRegistryTest {
    @Test
    fun retryIsPublishedHandledAndBoundToExistingMediaIdentity() {
        val registry = File("src/main/java/app/movia/android/agent/AgentActionRegistry.kt").readText()
        val runtime = File("src/main/java/app/movia/android/agent/AgentControlRuntime.kt").readText()
        val state = File("src/main/java/app/movia/android/agent/AgentStateRepository.kt").readText()

        assertTrue(registry.contains("AgentActionDefinition(\"player.retry\", AgentSafety.SAFE_WRITE"))
        assertTrue(runtime.contains("\"player.retry\" ->"))
        assertTrue(runtime.contains("session!!.retry()"))
        assertTrue(state.contains("\"player.retry\""))
        assertTrue(state.contains("-> hasMedia"))
    }
}
