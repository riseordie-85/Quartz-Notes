package com.quartz.notes.ui.theme

import android.os.Build
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.quartz.notes.data.AppSettings

/** Colors the Material scheme doesn't cover. */
@Immutable
data class QuartzColors(
    val isDark: Boolean,
    val card: Color,
    val pill: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val divider: Color,
    val pageLine: Color,
)

val LocalQuartzColors = staticCompositionLocalOf {
    QuartzColors(
        isDark = false,
        card = Color.White,
        pill = Color.White,
        textPrimary = Color(0xFF111114),
        textSecondary = Color(0xFF6B6B72),
        divider = Color(0xFFE2E2E6),
        pageLine = Color(0xFFB9B9BF),
    )
}

private val LightBackground = Color(0xFFEFEFF1)
private val DarkBackground = Color(0xFF101013)
private val DarkSurface = Color(0xFF1C1C21)

private val QuartzTypography = Typography().let { base ->
    base.copy(
        headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.Medium),
        titleLarge = base.titleLarge.copy(fontWeight = FontWeight.Medium, fontSize = 24.sp),
        titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    )
}

/** Large, airy label style used for section titles ("Quick Notes", dates). */
val SectionTitleStyle = TextStyle(fontSize = 26.sp, fontWeight = FontWeight.Normal, letterSpacing = 0.5.sp)

@Composable
fun QuartzTheme(settings: AppSettings, dark: Boolean, content: @Composable () -> Unit) {
    val accent = Color(settings.accentArgb)
    val context = LocalContext.current
    var scheme = when {
        settings.dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> darkColorScheme(
            primary = accent,
            onPrimary = Color.White,
            secondary = accent,
            background = DarkBackground,
            onBackground = Color(0xFFEDEDF0),
            surface = DarkSurface,
            onSurface = Color(0xFFEDEDF0),
            surfaceVariant = Color(0xFF2A2A31),
            onSurfaceVariant = Color(0xFFB4B4BC),
            surfaceContainerLow = DarkSurface,
            surfaceContainer = Color(0xFF222228),
            surfaceContainerHigh = Color(0xFF26262C),
            outline = Color(0xFF55555E),
            outlineVariant = Color(0xFF34343B),
        )
        else -> lightColorScheme(
            primary = accent,
            onPrimary = Color.White,
            secondary = accent,
            background = LightBackground,
            onBackground = Color(0xFF111114),
            surface = Color.White,
            onSurface = Color(0xFF111114),
            surfaceVariant = Color(0xFFE6E6EA),
            onSurfaceVariant = Color(0xFF5E5E66),
            surfaceContainerLow = Color.White,
            surfaceContainer = Color(0xFFF6F6F8),
            surfaceContainerHigh = Color(0xFFF2F2F5),
            outline = Color(0xFFB0B0B8),
            outlineVariant = Color(0xFFDDDDE2),
        )
    }
    if (settings.backgroundArgb != 0) scheme = scheme.copy(background = Color(settings.backgroundArgb))

    val extra = QuartzColors(
        isDark = dark,
        card = scheme.surface,
        pill = scheme.surface,
        textPrimary = scheme.onSurface,
        textSecondary = scheme.onSurfaceVariant,
        divider = if (dark) Color(0xFF2E2E35) else Color(0xFFE2E2E6),
        pageLine = if (dark) Color(0xFF4A4A52) else Color(0xFFB9B9BF),
    )

    MaterialTheme(colorScheme = scheme, typography = QuartzTypography) {
        CompositionLocalProvider(LocalQuartzColors provides extra, content = content)
    }
}
