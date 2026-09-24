package app.movia.android.agent

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
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
}
