package com.octop.client.ui.theme

import android.app.Activity
import android.content.ContextWrapper
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val DarkColorScheme = darkColorScheme(
    primary = PrimaryGreen,
    secondary = Color(0xFF64B5F6),
    tertiary = Color(0xFF81C784),
    background = Color(0xFF121418),
    surface = Color(0xFF1E2229),
    onPrimary = Color.White,
    onBackground = Color(0xFFE2E4E9),
    onSurface = Color(0xFFE2E4E9)
)

private val LightColorScheme = lightColorScheme(
    primary = PrimaryGreen,
    secondary = Color(0xFF1976D2),
    tertiary = Color(0xFF388E3C),
    background = Color(0xFFF9FAFB),
    surface = Color.White,
    onPrimary = Color.White,
    onBackground = Color(0xFF1C1E21),
    onSurface = Color(0xFF1C1E21)
)

@Composable
fun OctopTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            var ctx = view.context
            while (ctx is ContextWrapper) {
                if (ctx is Activity) break
                ctx = ctx.baseContext
            }
            (ctx as? Activity)?.window?.let { window ->
                val barColor = colorScheme.surface.toArgb()
                window.statusBarColor = barColor
                window.navigationBarColor = barColor
                val insetsController = WindowCompat.getInsetsController(window, view)
                insetsController.isAppearanceLightStatusBars = !darkTheme
                insetsController.isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}
