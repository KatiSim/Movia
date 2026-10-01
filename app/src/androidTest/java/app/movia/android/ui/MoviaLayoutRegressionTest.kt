package app.movia.android.ui
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.test.*
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.test.platform.app.InstrumentationRegistry
import android.graphics.Bitmap
import java.io.File
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.activity.compose.setContent
import app.movia.android.MainActivity
import app.movia.android.ui.theme.MoviaTheme
import org.junit.Rule
import org.junit.Test
class MoviaLayoutRegressionTest {
 @get:Rule val rule=createAndroidComposeRule<MainActivity>()
 private fun show(scale: Float) {
   // Dispose the activity's initial root before replacing it: MoviaApp owns
   // the shared PlaybackSession and must release it before a new root obtains it.
   rule.runOnUiThread {
     // Render only this test activity above the keyguard; do not dismiss it,
     // disable device security or change the user's screen/lock settings.
     rule.activity.setShowWhenLocked(true)
     rule.activity.setTurnScreenOn(true)
     rule.activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
   }
   rule.waitUntil(15_000L) {
     var resumed=false
     rule.runOnUiThread { resumed=rule.activity.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED) && rule.activity.hasWindowFocus() }
     resumed
   }
   rule.runOnUiThread { rule.activity.setContent {} }
   rule.waitForIdle()
   rule.runOnUiThread {
     rule.activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
     rule.activity.setContent { val density=LocalDensity.current;CompositionLocalProvider(LocalDensity provides Density(density.density,scale)) { MoviaTheme { MoviaApp() } } }
   }
   rule.waitUntil(15_000L) { runCatching { rule.onAllNodesWithTag("navigation.home",useUnmergedTree=true).fetchSemanticsNodes().isNotEmpty() }.getOrDefault(false) }
 }
 private fun navigation() {
   for(tag in listOf("navigation.catalog","navigation.library","navigation.home")) {
     rule.onNodeWithTag(tag,useUnmergedTree=true).assertIsDisplayed().performClick()
     rule.waitForIdle()
   }
 }
 private fun capture(name: String, tag: String? = null) {
   val node=if(tag==null) rule.onRoot() else rule.onNodeWithTag(tag,useUnmergedTree=true)
   val bitmap=node.captureToImage().asAndroidBitmap()
   File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir,"movia_qa_"+name+".png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
 }
 @Test fun primaryDestinationsRenderAndRespond() { show(1f);navigation();capture("home") }
 @Test fun navigationRemainsReachableWithLargeFonts() {
   show(2f);navigation()
   val layouts=mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
   rule.onNodeWithText("Смотреть",useUnmergedTree=true).performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { it(layouts) }
   org.junit.Assert.assertTrue("Play label has no text layout",layouts.isNotEmpty())
   for(l in layouts) android.util.Log.i("MoviaQaLayout","width="+l.size.width+" height="+l.size.height+" intrinsic="+l.multiParagraph.intrinsics.maxIntrinsicWidth+" overflowWidth="+l.didOverflowWidth+" overflowHeight="+l.didOverflowHeight+" ellipsis="+l.isLineEllipsized(0)+" font="+l.layoutInput.style.fontSize+" line="+l.layoutInput.style.lineHeight+" constraints="+l.layoutInput.constraints)
   capture("home_large_font")
   // Compare visible line bounds with a one-pixel rounding tolerance. A
   // fractional paragraph width can set didOverflowWidth without clipping.
   org.junit.Assert.assertFalse("Play label is ellipsized at 200% font",layouts.any { it.isLineEllipsized(0) })
   org.junit.Assert.assertFalse("Play label is vertically clipped",layouts.any { it.didOverflowHeight })
   org.junit.Assert.assertTrue("Play glyphs exceed the label bounds",layouts.all { it.getLineLeft(0)>=-1f && it.getLineRight(0)<=it.size.width+1f })
   capture("home_large_font")
 }
 @Test fun filtersRemainReachableWithLargeFonts() {
   show(1.5f)
   rule.onNodeWithTag("navigation.catalog",useUnmergedTree=true).performClick()
   rule.onNodeWithTag("catalog.filter",useUnmergedTree=true).assertIsDisplayed().performClick()
   rule.onNodeWithTag("catalog.filter.sheet",useUnmergedTree=true).assertIsDisplayed()
   rule.onNodeWithTag("catalog.filter.apply",useUnmergedTree=true).assertIsDisplayed()
   rule.onNodeWithText("Показать 0",useUnmergedTree=true).assertDoesNotExist()
   capture("filters_large_font","catalog.filter.sheet")
   rule.onNodeWithTag("catalog.filter.close",useUnmergedTree=true).performClick()
 }
 @Test fun playbackOptionsRemainReachableWithLargeFonts() {
   show(1f)
   var speed=1f;var subtitle="Нет";var voice="Studio A";var quality="Auto";var source="HDRezka"
   rule.runOnUiThread { rule.activity.setContent {} }
   rule.waitForIdle()
   rule.runOnUiThread {
     rule.activity.setContent {
       val density=LocalDensity.current
       CompositionLocalProvider(LocalDensity provides Density(density.density,2f)) {
         MoviaTheme {
           app.movia.android.ui.player.StreamSettingsScreen(
             audioOptions=listOf("Studio A","Studio B"),qualityOptions=listOf("Auto","360p","720p"),
             selectedAudio="Studio A",selectedQuality="Auto",autoNextEnabled=false,persistentSeekButtons=false,
             onBack={},onAudioSelected={voice=it},onQualitySelected={quality=it},
             onAutoNextChanged={},onPersistentSeekButtonsChanged={},
             subtitleOptions=listOf("Нет","Auto","Русские"),onSubtitleSelected={subtitle=it},onSpeedSelected={speed=it},
             sourceOptions=listOf("HDRezka","Filmix"),selectedSource="HDRezka",onSourceSelected={source=it},
           )
         }
       }
     }
   }
   for((label,row,index) in listOf(Triple("ИСТОЧНИК","source",1),Triple("ОЗВУЧКА","voice",1),Triple("КАЧЕСТВО ВИДЕО","quality",2),
     Triple("СКОРОСТЬ","speed",4),Triple("СУБТИТРЫ","subtitle",2))) {
     rule.onNodeWithText(label,useUnmergedTree=true).performScrollTo()
     rule.onNodeWithTag("settings.$row.row",useUnmergedTree=true).performScrollToIndex(index)
     val text=when(row){"source"->"Filmix";"voice"->"Studio B";"quality"->"720p";"speed"->"1.5×";else->"Русские"}
     rule.onNodeWithText(text,useUnmergedTree=true).assertIsDisplayed().performClick()
   }
   org.junit.Assert.assertEquals("Studio B",voice)
   org.junit.Assert.assertEquals("720p",quality)
   org.junit.Assert.assertEquals(1.5f,speed,0.001f)
   org.junit.Assert.assertEquals("Русские",subtitle)
   org.junit.Assert.assertEquals("Filmix",source)
   capture("playback_options_large_font")
 }
}
