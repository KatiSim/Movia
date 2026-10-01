package app.movia.android.agent

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertEquals
import app.movia.android.ui.player.MoviaPlaybackRegistry
import org.junit.Assert.assertTrue
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AgentControlContractTest {
    @Test
    fun everyPublishedControlHasARegisteredActionAndResolvableTag() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val manifest = AgentStateRepository(context, AgentEventBus()).controlsManifestJson()
        val controls = manifest.getJSONArray("controls")

        assertTrue("Expected the full stable control manifest", controls.length() >= 41)

        for (index in 0 until controls.length()) {
            val control = controls.getJSONObject(index)
            val controlId = control.getString("id")
            val actionId = control.getString("actionId")
            assertNotNull("$controlId refers to unregistered action $actionId", AgentActionRegistry.find(actionId))

            val isResolvable = control.has("testTag") || control.has("testTagPattern")
            assertTrue("$controlId has no Compose test tag or dynamic tag pattern", isResolvable)
            if (control.optBoolean("dynamic")) {
                assertTrue("$controlId must publish a dynamic tag pattern", control.has("testTagPattern"))
            }
        }
    }
    @Test
    fun diagnosticsReportsActualNumericPlaybackSpeed() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        var previous = 1f
        instrumentation.runOnMainSync {
            val session = MoviaPlaybackRegistry.obtain(instrumentation.targetContext)
            previous = session.player.playbackParameters.speed
            session.setPlaybackSpeed(1.5f)
        }
        try {
            val media = AgentControlRuntime.diagnosticsJson().getJSONObject("media3")
            assertTrue("Playback speed must be numeric", media.get("speed") is Number)
            assertEquals(1.5, media.getDouble("speed"), 0.001)
            instrumentation.runOnMainSync { MoviaPlaybackRegistry.current!!.setPlaybackSpeed(0.75f) }
            assertEquals(0.75, AgentControlRuntime.diagnosticsJson().getJSONObject("media3").getDouble("speed"), 0.001)
        } finally {
            instrumentation.runOnMainSync { MoviaPlaybackRegistry.current!!.setPlaybackSpeed(previous) }
        }
    }

    @Test
    fun stopIsIdempotentAndCancelsAnAcceptedLookup() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        AgentControlRuntime.start(instrumentation.targetContext)
        fun action(name: String, arguments: JSONObject = JSONObject()) =
            AgentControlRuntime.dispatch(JSONObject().put("action", name).put("arguments", arguments))
        assertEquals("completed", action("player.stop").getString("status"))
        val started = action("media.play", JSONObject()
            .put("mediaId", "movia_qa_stop_pending").put("title", "Movia QA stop pending")
            .put("recordHistory", false).put("resume", false).put("persist", false))
        assertEquals("accepted", started.getString("status"))
        assertEquals("completed", action("player.stop").getString("status"))
        val operation = AgentControlRuntime.operationJson(started.getString("operationId"))!!
        assertEquals("FAILED", operation.getString("status"))
        assertEquals("SELECTION_STOPPED", operation.getString("errorCode"))
        Thread.sleep(500L)
        instrumentation.runOnMainSync {
            assertTrue(MoviaPlaybackRegistry.current?.state?.value?.hasMedia != true)
        }
        assertEquals("completed", action("player.stop").getString("status"))
    }

}
