package app.movia.android.agent
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONObject
@RunWith(AndroidJUnit4::class)
class AgentSchemaRegressionTest {
 private fun accepts(action: String, raw: String): Boolean = runCatching { AgentSchemaValidator.validate(JSONObject(raw),AgentActionRegistry.find(action)!!.schema) }.isSuccess
 @Test fun booleansAreNotCoercedFromTextOrNumbers() {
   assertFalse(accepts("media.play","{\"mediaId\":\"1\",\"recordHistory\":\"false\"}"))
   assertFalse(accepts("media.play","{\"mediaId\":\"1\",\"resume\":0}"))
   assertTrue(accepts("media.play","{\"mediaId\":\"1\",\"recordHistory\":false}"))
 }
 @Test fun pagingAndEpisodeNumbersRequireIntegralValues() {
   for(raw in listOf("{\"offset\":-1}","{\"limit\":101}","{\"limit\":1.5}","{\"limit\":\"20\"}")) assertFalse(accepts("catalog.query",raw))
   assertFalse(accepts("media.play","{\"mediaId\":\"1\",\"season\":0}"))
 }
 @Test fun unknownFieldsAndMissingRequiredValuesAreRejected() {
   assertFalse(accepts("player.selectQuality","{}"))
   assertFalse(accepts("player.selectQuality","{\"quality\":\"720p\",\"unknown\":true}"))
   assertFalse(accepts("player.selectQuality","{\"quality\":null}"))
 }
 @Test fun oversizedArgumentsAreRejected() { assertFalse(accepts("player.selectVoice",JSONObject().put("voice","x".repeat(4097)).toString())) }
}
