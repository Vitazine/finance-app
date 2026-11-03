package app.finance.android

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val LightColors = lightColorScheme(
    primary = Color(0xFFB22222),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFDAD6),
    onPrimaryContainer = Color(0xFF410004),
    secondary = Color(0xFF775651),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFDAD4),
    onSecondaryContainer = Color(0xFF2C1512),
    tertiary = Color(0xFF705C2E),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFBDFA6),
    onTertiaryContainer = Color(0xFF261A00),
    background = Color(0xFFFFF8F6),
    onBackground = Color(0xFF251917),
    surface = Color(0xFFFFF8F6),
    onSurface = Color(0xFF251917),
    surfaceVariant = Color(0xFFF5DDD8),
    onSurfaceVariant = Color(0xFF58413D),
    surfaceTint = Color(0xFFB22222),
    surfaceBright = Color(0xFFFFF8F6),
    surfaceDim = Color(0xFFE8D6D2),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF3F3F3),
    surfaceContainer = Color(0xFFFCE9E5),
    surfaceContainerHigh = Color(0xFFF6E3DF),
    surfaceContainerHighest = Color(0xFFF0DDD9),
    outline = Color(0xFF8B716B),
    outlineVariant = Color(0xFFDCC2BC),
    inverseSurface = Color(0xFF3B2D2A),
    inverseOnSurface = Color(0xFFFFEDEA),
    inversePrimary = Color(0xFFFFB4AB),
    error = Color(0xFFBA1A1A),
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002)
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFFFB4AB),
    onPrimary = Color(0xFF690007),
    primaryContainer = Color(0xFF8F1518),
    onPrimaryContainer = Color(0xFFFFEDEA),
    secondary = Color(0xFFE7BDB5),
    onSecondary = Color(0xFF442A25),
    secondaryContainer = Color(0xFF5D3F3A),
    onSecondaryContainer = Color(0xFFFFDAD4),
    tertiary = Color(0xFFDFC38C),
    onTertiary = Color(0xFF3E2E05),
    tertiaryContainer = Color(0xFF56451A),
    onTertiaryContainer = Color(0xFFFBDFA6),
    background = Color(0xFF1C1110),
    onBackground = Color(0xFFF9DCD6),
    surface = Color(0xFF1C1110),
    onSurface = Color(0xFFF9DCD6),
    surfaceVariant = Color(0xFF58413D),
    onSurfaceVariant = Color(0xFFDCC2BC),
    surfaceTint = Color(0xFFFFB4AB),
    surfaceBright = Color(0xFF453532),
    surfaceDim = Color(0xFF1C1110),
    surfaceContainerLowest = Color(0xFF160C0A),
    surfaceContainerLow = Color(0xFF252525),
    surfaceContainer = Color(0xFF2A1D1B),
    surfaceContainerHigh = Color(0xFF352825),
    surfaceContainerHighest = Color(0xFF41322F),
    outline = Color(0xFFA58A84),
    outlineVariant = Color(0xFF58413D),
    inverseSurface = Color(0xFFF9DCD6),
    inverseOnSurface = Color(0xFF3B2D2A),
    inversePrimary = Color(0xFFB22222),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6)
)

@Composable
fun FinanceTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val view = LocalView.current
    if (!view.isInEditMode) {
        val window = view.context.activity()?.window
        SideEffect {
            if (window != null) {
                WindowCompat.getInsetsController(window, view).apply {
                    isAppearanceLightStatusBars = !darkTheme
                    isAppearanceLightNavigationBars = !darkTheme
                }
            }
        }
    }
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content
    )
}

private tailrec fun Context.activity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.activity()
    else -> null
}
