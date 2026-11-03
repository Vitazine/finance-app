package app.finance.android

import androidx.activity.ComponentActivity
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.view.WindowCompat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class FinanceThemeTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun systemBarsFollowAppThemeEvenWhenItDiffersFromSystem() {
        val dark = mutableStateOf(false)
        val controller = WindowCompat.getInsetsController(
            compose.activity.window,
            compose.activity.window.decorView
        )
        compose.runOnUiThread {
            controller.isAppearanceLightStatusBars = false
            controller.isAppearanceLightNavigationBars = false
        }
        compose.setContent { FinanceTheme(dark.value) {} }
        for (mode in listOf(false, true, false)) {
            compose.runOnIdle { dark.value = mode }
            compose.runOnIdle {
                assertEquals(!mode, controller.isAppearanceLightStatusBars)
                assertEquals(!mode, controller.isAppearanceLightNavigationBars)
            }
        }
    }

    @Test fun brandAndBothThemesKeepReadableSemanticColors() {
        val dark = mutableStateOf(false)
        lateinit var colors: ColorScheme
        compose.setContent { FinanceTheme(dark.value) { colors = MaterialTheme.colorScheme } }
        compose.runOnIdle { assertEquals(Color(0xFFB22222), colors.primary) }
        for (mode in listOf(false, true)) {
            compose.runOnIdle { dark.value = mode }
            compose.runOnIdle {
                val pairs = listOf(
                    colors.primary to colors.onPrimary,
                    colors.primaryContainer to colors.onPrimaryContainer,
                    colors.secondaryContainer to colors.onSecondaryContainer,
                    colors.tertiaryContainer to colors.onTertiaryContainer,
                    colors.surface to colors.onSurface,
                    colors.surface to colors.onSurfaceVariant,
                    colors.surfaceContainer to colors.onSurface,
                    colors.surfaceContainerLow to colors.onSurface,
                    colors.error to colors.onError,
                    colors.errorContainer to colors.onErrorContainer
                )
                pairs.forEach { (background, foreground) ->
                    val a = background.luminance()
                    val b = foreground.luminance()
                    val contrast = (maxOf(a, b) + 0.05f) / (minOf(a, b) + 0.05f)
                    assertTrue("Text contrast $contrast in dark=$mode", contrast >= 4.5f)
                }
            }
        }
    }
}
