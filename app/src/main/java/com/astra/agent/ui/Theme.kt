package com.astra.agent.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Immutable
class IosColors(
    val bg: Color, val card: Color, val fill: Color, val text: Color, val sub: Color, val line: Color,
    val accent: Color, val onAccent: Color, val green: Color, val red: Color, val orange: Color, val dark: Boolean
)

/**
 * The one place that decides whether destructive actions and hard errors use a restrained red.
 * Set USE_RED to false for a pure black-and-white app: [danger] then falls back to a neutral gray.
 */
object DangerStyle {
    const val USE_RED = true
    val RED_ON_DARK = Color(0xFFFF5A52)
    val RED_ON_LIGHT = Color(0xFFC62828)
}

val IosColors.danger: Color get() = if (!DangerStyle.USE_RED) red else if (dark) DangerStyle.RED_ON_DARK else DangerStyle.RED_ON_LIGHT

/** Icon/text colour that stays visible on a filled tile of any tone (accent is white in the dark theme). */
fun contentOn(color: Color): Color = if (color.luminance() > 0.5f) Color.Black else Color.White

// Every value below is a neutral gray (R = G = B). Status is shown with icons, labels and fill style, not hue.
val DarkIos = IosColors(
    bg = Color(0xFF000000), card = Color(0xFF111111), fill = Color(0xFF1A1A1A), text = Color(0xFFFFFFFF), sub = Color(0xFF8A8A8A),
    line = Color(0xFF2E2E2E), accent = Color(0xFFFFFFFF), onAccent = Color(0xFF000000),
    green = Color(0xFFD0D0D0), red = Color(0xFFB0B0B0), orange = Color(0xFF9E9E9E), dark = true)
val LightIos = IosColors(
    bg = Color(0xFFFFFFFF), card = Color(0xFFF5F5F5), fill = Color(0xFFEBEBEB), text = Color(0xFF000000), sub = Color(0xFF666666),
    line = Color(0xFFD0D0D0), accent = Color(0xFF000000), onAccent = Color(0xFFFFFFFF),
    green = Color(0xFF333333), red = Color(0xFF444444), orange = Color(0xFF666666), dark = false)

val LocalIos = staticCompositionLocalOf { LightIos }

/** Every Material 3 colour role is set to a gray, so no default purple or blue can show up in any component. */
@Composable
fun AstraTheme(dark: Boolean, content: @Composable () -> Unit) {
    val c = if (dark) DarkIos else LightIos
    val scheme = if (dark) darkColorScheme(
        primary = c.accent, onPrimary = c.onAccent, primaryContainer = c.fill, onPrimaryContainer = c.text,
        secondary = c.sub, onSecondary = c.bg, secondaryContainer = c.fill, onSecondaryContainer = c.text,
        tertiary = c.sub, onTertiary = c.bg, tertiaryContainer = c.fill, onTertiaryContainer = c.text,
        background = c.bg, onBackground = c.text, surface = c.bg, onSurface = c.text, surfaceVariant = c.card, onSurfaceVariant = c.sub,
        surfaceTint = c.accent, inverseSurface = c.text, inverseOnSurface = c.bg, inversePrimary = c.onAccent,
        error = c.danger, onError = c.bg, errorContainer = c.fill, onErrorContainer = c.danger,
        outline = c.sub, outlineVariant = c.line, scrim = Color(0xFF000000),
        surfaceBright = c.fill, surfaceDim = c.bg, surfaceContainer = c.card, surfaceContainerHigh = c.fill, surfaceContainerHighest = c.fill,
        surfaceContainerLow = c.card, surfaceContainerLowest = c.bg)
    else lightColorScheme(
        primary = c.accent, onPrimary = c.onAccent, primaryContainer = c.fill, onPrimaryContainer = c.text,
        secondary = c.sub, onSecondary = c.bg, secondaryContainer = c.fill, onSecondaryContainer = c.text,
        tertiary = c.sub, onTertiary = c.bg, tertiaryContainer = c.fill, onTertiaryContainer = c.text,
        background = c.bg, onBackground = c.text, surface = c.bg, onSurface = c.text, surfaceVariant = c.card, onSurfaceVariant = c.sub,
        surfaceTint = c.accent, inverseSurface = c.text, inverseOnSurface = c.bg, inversePrimary = c.onAccent,
        error = c.danger, onError = c.bg, errorContainer = c.fill, onErrorContainer = c.danger,
        outline = c.sub, outlineVariant = c.line, scrim = Color(0xFF000000),
        surfaceBright = c.bg, surfaceDim = c.fill, surfaceContainer = c.card, surfaceContainerHigh = c.fill, surfaceContainerHighest = c.fill,
        surfaceContainerLow = c.card, surfaceContainerLowest = c.bg)
    val selection = androidx.compose.foundation.text.selection.TextSelectionColors(handleColor = c.text, backgroundColor = c.text.copy(alpha = 0.28f))
    CompositionLocalProvider(LocalIos provides c, androidx.compose.foundation.text.selection.LocalTextSelectionColors provides selection) {
        MaterialTheme(colorScheme = scheme) { Surface(Modifier.fillMaxSize(), color = c.bg, contentColor = c.text, content = content) }
    }
}

val IosSpring = spring<Float>(dampingRatio = 0.62f, stiffness = Spring.StiffnessMediumLow)

/** iOS-style press: springs down to 94% and bounces back. */
fun Modifier.pressScale(enabled: Boolean = true, onClick: () -> Unit): Modifier = composed {
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val s by animateFloatAsState(if (pressed) 0.93f else 1f, IosSpring, label = "press")
    this.graphicsLayer { scaleX = s; scaleY = s }.clickable(src, null, enabled = enabled, onClick = onClick)
}

@Composable
fun SectionHeader(text: String) {
    Text(text.uppercase(), Modifier.padding(start = 32.dp, top = 22.dp, bottom = 6.dp),
        fontSize = 13.sp, color = LocalIos.current.sub, letterSpacing = 0.3.sp)
}

@Composable
fun Group(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp))
        .background(LocalIos.current.card).animateContentSize(), content = content)
}

@Composable
fun Hairline() {
    Box(Modifier.padding(start = 16.dp).fillMaxWidth().height(0.5.dp).background(LocalIos.current.line))
}

@Composable
fun PillButton(text: String, color: Color = LocalIos.current.accent, enabled: Boolean = true,
               modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(modifier.pressScale(enabled, onClick).clip(CircleShape).background(color.copy(alpha = if (enabled) 0.15f else 0.07f))
        .padding(horizontal = 16.dp, vertical = 7.dp), contentAlignment = Alignment.Center) {
        Text(text, color = if (enabled) color else color.copy(alpha = 0.4f), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    }
}
