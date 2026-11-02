package app.finance.android

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LargeFontNavigationTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun navigationLabelsStayReadableAtTwoHundredPercent() {
        compose.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, fontScale = 2f)) {
                FinanceTheme {
                    FinanceNavigation(app.finance.presentation.Destination.HOME) { }
                }
            }
        }

        listOf("Главная", "Операции", "Бюджет", "AI", "Настройки").forEach { label ->
            val layouts = mutableListOf<TextLayoutResult>()
            compose.onNodeWithText(label).assertIsDisplayed()
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { action ->
                    action(layouts)
                }
            assertEquals(
                "Navigation label must not split words: $label",
                1,
                layouts.single().lineCount
            )
        }
    }
}
