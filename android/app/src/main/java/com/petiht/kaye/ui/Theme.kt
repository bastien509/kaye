package com.petiht.kaye.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** A Haitian school notebook: blue ruled lines, a red margin, a yellow highlighter. */
object K {
    val Blue = Color(0xFF00209F)
    val Red = Color(0xFFD21034)
    val Sun = Color(0xFFF5C518)
    val Green = Color(0xFF0D8048)
    val GreenDark = Color(0xFF4CC98A)
    val Amber = Color(0xFFA35A00)
    val Ink = Color(0xFF0B1533)
    val Paper2 = Color(0xFFF5F7FC)
}

data class Kc(
    val paper: Color, val paper2: Color, val ink: Color, val muted: Color, val line: Color,
    val accent: Color, val onAccent: Color, val accentSoft: Color,
    val cta: Color, val green: Color, val greenSoft: Color, val amber: Color, val amberSoft: Color, val sunSoft: Color,
    val rule: Color, val margin: Color, val dark: Boolean,
)

private val LightC = Kc(
    paper = Color.White, paper2 = K.Paper2, ink = K.Ink, muted = Color(0xFF5B6478), line = Color(0xFFE2E7F3),
    accent = K.Blue, onAccent = Color.White, accentSoft = Color(0xFFE9EDFB),
    cta = K.Red, green = K.Green, greenSoft = Color(0xFFE4F4EB), amber = K.Amber, amberSoft = Color(0xFFFFF1DC), sunSoft = Color(0xFFFFF5CF),
    rule = Color(0x1300209F), margin = Color(0x73D21034), dark = false,
)
// Dark: matte background, yellow accents, red for main actions, no blue.
private val DarkC = Kc(
    paper = Color(0xFF1A1B1F), paper2 = Color(0xFF121316), ink = Color(0xFFF2F4F8), muted = Color(0xFFA2A8B6), line = Color(0xFF2B2D34),
    accent = K.Sun, onAccent = Color(0xFF15130A), accentSoft = Color(0x1FF5C518),
    cta = Color(0xFFE0284A), green = K.GreenDark, greenSoft = Color(0x1F4CC98A), amber = Color(0xFFF0A644), amberSoft = Color(0x1FF0A644), sunSoft = Color(0x29F5C518),
    rule = Color(0x0DFFFFFF), margin = Color(0x80E0284A), dark = true,
)

val kc: Kc
    @Composable @ReadOnlyComposable get() = if (isSystemInDarkTheme()) DarkC else LightC

val Mono = FontFamily.Monospace
val Hand = FontFamily.Cursive

@Composable
fun KayeTheme(content: @Composable () -> Unit) {
    val c = kc
    val scheme = if (c.dark) darkColorScheme(
        primary = c.accent, onPrimary = c.onAccent, secondary = c.cta, onSecondary = Color.White,
        background = c.paper2, surface = c.paper, onSurface = c.ink, onBackground = c.ink, surfaceVariant = c.paper,
        primaryContainer = c.accentSoft, onPrimaryContainer = c.accent, secondaryContainer = c.accentSoft, onSecondaryContainer = c.accent,
        surfaceContainer = c.paper, surfaceContainerHigh = c.paper, surfaceContainerHighest = c.paper, surfaceContainerLow = c.paper2, surfaceContainerLowest = c.paper2,
        outline = c.line, outlineVariant = c.line, error = c.cta, tertiary = c.green, onSurfaceVariant = c.muted,
    ) else lightColorScheme(
        primary = c.accent, onPrimary = c.onAccent, secondary = c.cta, onSecondary = Color.White,
        background = c.paper2, surface = c.paper, onSurface = c.ink, onBackground = c.ink, surfaceVariant = c.paper2,
        primaryContainer = c.accentSoft, onPrimaryContainer = c.accent, secondaryContainer = c.accentSoft, onSecondaryContainer = c.accent,
        surfaceContainer = c.paper, surfaceContainerHigh = c.paper, surfaceContainerHighest = c.paper2, surfaceContainerLow = c.paper2, surfaceContainerLowest = Color.White,
        outline = c.line, outlineVariant = c.line, error = c.cta, tertiary = c.green, onSurfaceVariant = c.muted,
    )
    val t = Typography()
    MaterialTheme(
        colorScheme = scheme,
        typography = t.copy(
            headlineMedium = t.headlineMedium.copy(fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.5).sp),
            titleLarge = t.titleLarge.copy(fontWeight = FontWeight.ExtraBold),
            titleMedium = t.titleMedium.copy(fontWeight = FontWeight.Bold),
        ),
        content = content,
    )
}

val HandStyle = TextStyle(fontFamily = Hand, fontWeight = FontWeight.Bold, fontSize = 24.sp)
