package com.example.coursetable

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext

private val LightScheme = lightColorScheme(
    primary = Color(0xFF3F5BD0),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDDE1FF),
    onPrimaryContainer = Color(0xFF00164F),
    secondary = Color(0xFF5A5D72),
    secondaryContainer = Color(0xFFDFE1F9),
    tertiary = Color(0xFF76546E),
    tertiaryContainer = Color(0xFFFFD7F3),
    background = Color(0xFFFBF8FF),
    surface = Color(0xFFFBF8FF),
    surfaceVariant = Color(0xFFE2E1EC),
    onSurfaceVariant = Color(0xFF45464F)
)

private val DarkScheme = darkColorScheme(
    primary = Color(0xFFB9C3FF),
    onPrimary = Color(0xFF07277F),
    primaryContainer = Color(0xFF2641B7),
    onPrimaryContainer = Color(0xFFDDE1FF),
    secondary = Color(0xFFC3C5DD),
    secondaryContainer = Color(0xFF424659),
    tertiary = Color(0xFFE5BAD8),
    tertiaryContainer = Color(0xFF5C3C55),
    background = Color(0xFF121318),
    surface = Color(0xFF121318),
    surfaceVariant = Color(0xFF45464F),
    onSurfaceVariant = Color(0xFFC6C5D0)
)

@Composable
fun CourseTableTheme(
    settings: AppSettings,
    content: @Composable () -> Unit
) {
    val darkTheme = when (settings.darkMode) {
        1 -> false
        2 -> true
        else -> isSystemInDarkTheme()
    }
    val context = LocalContext.current
    val colorScheme: ColorScheme = when {
        settings.dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        darkTheme -> DarkScheme
        else -> LightScheme
    }
    MaterialTheme(colorScheme = colorScheme, content = content)
}

/** 课程色板（浅色 / 深色各一套） */
object CourseColors {
    private val light = listOf(
        Color(0xFF5B7BF5), Color(0xFFEF7A7A), Color(0xFF41B883), Color(0xFFF2A33C), Color(0xFF9B6BF2),
        Color(0xFF2EB8C9), Color(0xFFE5679E), Color(0xFF7FB069), Color(0xFFF08A4B), Color(0xFF4F8DD9)
    )
    private val dark = listOf(
        Color(0xFF4A66D6), Color(0xFFC95C5C), Color(0xFF2E9A6B), Color(0xFFD08A2A), Color(0xFF7D52CF),
        Color(0xFF2497A6), Color(0xFFC24F84), Color(0xFF649552), Color(0xFFD06F36), Color(0xFF3B74BD)
    )
    val size get() = light.size

    @Composable
    fun of(index: Int): Color {
        val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
        val list = if (isDark) dark else light
        return list[((index % list.size) + list.size) % list.size]
    }

    fun preview(index: Int): Color = light[((index % light.size) + light.size) % light.size]

    /** 供桌面小组件（RemoteViews）使用的 ARGB 颜色 */
    fun argb(index: Int, dark: Boolean): Int {
        val list = if (dark) this.dark else light
        return list[((index % list.size) + list.size) % list.size].value.shr(32).toInt()
    }
}
