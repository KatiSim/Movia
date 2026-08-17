package app.viora.android

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VioraNavigationTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun topLevelNavigationOpensEveryPrimaryDestination() {
        composeRule.onNodeWithText("Главная", useUnmergedTree = true)
            .assertExists()
            .assertIsSelected()

        listOf(
            "Каталог" to "Каталог",
            "Поиск" to "Популярное",
            "Моё" to "Сохранённое",
            "Профиль" to "Локальный профиль",
            "Главная" to "Продолжить просмотр",
        ).forEach { (destination, marker) ->
            composeRule.onNodeWithText(destination, useUnmergedTree = true)
                .performClick()
            composeRule.waitForIdle()
            composeRule.onNodeWithText(marker, useUnmergedTree = true)
                .assertExists()
        }
    }

    @Test
    fun searchUpdatesResultsWhileTyping() {
        composeRule.onNodeWithText("Поиск", useUnmergedTree = true).performClick()
        composeRule.waitForIdle()

        val field = composeRule.onNodeWithText(
            "Фильм, сериал, актёр или режиссёр",
            useUnmergedTree = true,
        )
        field.performClick()
        field.performTextInput("Граница миров")
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Граница миров", useUnmergedTree = true)
            .assertExists()
    }

    @Test
    fun seriesSeasonsCanBeCollapsedAndExpandedIndependently() {
        composeRule.onNodeWithText("Поиск", useUnmergedTree = true).performClick()
        composeRule.waitForIdle()

        val field = composeRule.onNodeWithText(
            "Фильм, сериал, актёр или режиссёр",
            useUnmergedTree = true,
        )
        field.performClick()
        field.performTextInput("Нулевая орбита")
        composeRule.waitForIdle()

        composeRule.onNode(
            hasText("Нулевая орбита") and hasClickAction(),
            useUnmergedTree = true,
        ).performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Сезон 1", useUnmergedTree = true)
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithText("Сезон 2", useUnmergedTree = true)
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithText("Сезон 3", useUnmergedTree = true)
            .performScrollTo()
            .assertIsDisplayed()

        // Season 1 is expanded initially for the current/first season.
        composeRule.onNodeWithText("S01E01 · Эпизод 1", useUnmergedTree = true)
            .assertExists()

        composeRule.onNodeWithContentDescription(
            "Сезон 1. 8 серий. Развёрнут. Нажмите, чтобы свернуть",
            useUnmergedTree = true,
        ).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("S01E01 · Эпизод 1", useUnmergedTree = true)
            .assertDoesNotExist()

        composeRule.onNodeWithContentDescription(
            "Сезон 2. 8 серий. Свёрнут. Нажмите, чтобы развернуть",
            useUnmergedTree = true,
        ).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("S02E01 · Эпизод 1", useUnmergedTree = true)
            .assertExists()

        // Expanding season 2 must not implicitly re-open season 1.
        composeRule.onNodeWithText("S01E01 · Эпизод 1", useUnmergedTree = true)
            .assertDoesNotExist()
    }
}
