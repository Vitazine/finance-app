package app.finance.android

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FoundationSmokeTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun opensAndNavigatesWithAvailableOrMissingModel() {
        compose.waitUntil(10000) {
            compose.onAllNodesWithText("Посмотреть операции").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Ваши финансы").assertIsDisplayed()
        compose.onNodeWithText("Посмотреть операции").performScrollTo().performClick()
        compose.onNodeWithTag("history_list").assertIsDisplayed()
        compose.onNodeWithTag("nav_AI").performClick()
        compose.waitUntil(30000) {
            compose.onAllNodesWithTag("ai_model_ready").fetchSemanticsNodes().isNotEmpty() ||
                compose.onAllNodesWithTag("ai_model_error").fetchSemanticsNodes().isNotEmpty()
        }
        val ready = compose.onAllNodesWithTag("ai_model_ready").fetchSemanticsNodes().isNotEmpty()
        compose.onNodeWithTag(if (ready) "ai_model_ready" else "ai_model_error")
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("ai_manual").performScrollTo().assertIsDisplayed().assertIsEnabled()
    }

    @Test
    fun selectedSectionSurvivesActivityRecreation() {
        compose.onNodeWithTag("nav_SETTINGS").performClick()
        compose.waitUntil(10000) {
            compose.onAllNodesWithText("Хранение на устройстве").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Хранение на устройстве").assertIsDisplayed()
        compose.activityRule.scenario.recreate()

        compose.waitUntil(10000) {
            compose.onAllNodesWithText("Хранение на устройстве").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Хранение на устройстве").assertIsDisplayed()
    }

    @Test
    fun backFromSectionReturnsToHome() {
        compose.onNodeWithTag("nav_ANALYTICS").performClick()
        compose.onNodeWithText("Бюджет ещё не задан").assertIsDisplayed()
        compose.activityRule.scenario.onActivity { activity ->
            activity.onBackPressedDispatcher.onBackPressed()
        }

        compose.waitUntil(10000) {
            compose.onAllNodesWithText("Ваши финансы").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Ваши финансы").assertIsDisplayed()
    }
}
