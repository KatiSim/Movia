package app.movia.android

import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MoviaNavigationTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun topLevelNavigationMatchesCurrentThreeDestinationArchitecture() {
        composeRule.onNodeWithText("Главная", useUnmergedTree = true)
            .assertExists()
            .assertIsSelected()

        listOf(
            "Каталог" to "Каталог",
            "Моё" to "Моё",
            "Главная" to "Movia",
        ).forEach { (destination, marker) ->
            composeRule.onNodeWithText(destination, useUnmergedTree = true)
                .performClick()
            composeRule.waitForIdle()
            composeRule.onNodeWithText(marker, useUnmergedTree = true)
                .assertExists()
        }
    }

    @Test
    fun catalogSearchStateSurvivesNormalTopLevelTabSwitching() {
        composeRule.onNodeWithText("Каталог", useUnmergedTree = true).performClick()
        composeRule.waitForIdle()

        val searchField = composeRule.onNode(hasSetTextAction(), useUnmergedTree = true)
        searchField.performClick()
        searchField.performTextInput("Мстители")
        composeRule.waitForIdle()
        searchField.assertTextContains("Мстители")

        composeRule.onNodeWithText("Моё", useUnmergedTree = true).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Каталог", useUnmergedTree = true).performClick()
        composeRule.waitForIdle()

        composeRule.onNode(hasSetTextAction(), useUnmergedTree = true)
            .assertTextContains("Мстители")
    }

    @Test
    fun libraryExposesCurrentUserFacingCollections() {
        composeRule.onNodeWithText("Моё", useUnmergedTree = true).performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Закладки", useUnmergedTree = true).assertExists()
        composeRule.onNodeWithText("Скачанное", useUnmergedTree = true).assertExists()
        composeRule.onNodeWithText("История", useUnmergedTree = true).assertExists()
    }
}
