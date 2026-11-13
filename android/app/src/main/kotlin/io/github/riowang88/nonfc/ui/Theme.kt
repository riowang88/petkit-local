package io.github.riowang88.nonfc.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * A warm editorial look: cream canvas, slightly darker cream cards, one coral button per
 * screen, and a dark surface kept for the log. Depth comes from the color blocks, so
 * nothing here casts a shadow. Headings are the phone's own serif, which on Android 9+
 * is Noto Serif CJK and therefore has the Chinese glyphs a bundled Latin face would lack.
 */
@Immutable
data class AppColors(
    val canvas: Color,
    val card: Color,
    val cardStrong: Color,
    val hairline: Color,
    val divider: Color,
    val ink: Color,
    val body: Color,
    val muted: Color,
    val mutedSoft: Color,
    val primary: Color,
    val primaryActive: Color,
    val primaryDisabled: Color,
    val onPrimary: Color,
    val log: Color,
    val onLog: Color,
    val onLogSoft: Color,
    val success: Color,
    val warning: Color,
    val error: Color,
)

private val Light = AppColors(
    canvas = Color(0xFFFAF9F5),
    card = Color(0xFFEFE9DE),
    cardStrong = Color(0xFFE8E0D2),
    hairline = Color(0xFFE6DFD8),
    divider = Color(0x338E8B82),
    ink = Color(0xFF141413),
    body = Color(0xFF3D3D3A),
    muted = Color(0xFF6C6A64),
    mutedSoft = Color(0xFF8E8B82),
    primary = Color(0xFFCC785C),
    primaryActive = Color(0xFFA9583E),
    primaryDisabled = Color(0xFFE6DFD8),
    onPrimary = Color.White,
    log = Color(0xFF181715),
    onLog = Color(0xFFFAF9F5),
    onLogSoft = Color(0xFFA09D96),
    success = Color(0xFF5DB872),
    warning = Color(0xFFD4A017),
    error = Color(0xFFC64545),
)

/** The same palette turned over: the dark product surfaces become the floor. */
private val Dark = AppColors(
    canvas = Color(0xFF181715),
    card = Color(0xFF252320),
    cardStrong = Color(0xFF3D3D3A),
    hairline = Color(0xFF3D3D3A),
    divider = Color(0xFF3D3D3A),
    ink = Color(0xFFFAF9F5),
    body = Color(0xFFE6DFD8),
    muted = Color(0xFFA09D96),
    mutedSoft = Color(0xFF8E8B82),
    primary = Color(0xFFCC785C),
    primaryActive = Color(0xFFA9583E),
    primaryDisabled = Color(0xFF3D3D3A),
    onPrimary = Color.White,
    log = Color(0xFF1F1E1B),
    onLog = Color(0xFFFAF9F5),
    onLogSoft = Color(0xFFA09D96),
    success = Color(0xFF5DB872),
    warning = Color(0xFFD4A017),
    error = Color(0xFFC64545),
)

val LocalApp = staticCompositionLocalOf { Light }

object App {
    val colors: AppColors @Composable get() = LocalApp.current

    // Display type is regular weight with tightened tracking; emphasis comes from size.
    val displayLg = TextStyle(fontFamily = FontFamily.Serif, fontSize = 30.sp, lineHeight = 36.sp, letterSpacing = (-0.5).sp)
    val displayMd = TextStyle(fontFamily = FontFamily.Serif, fontSize = 22.sp, lineHeight = 28.sp, letterSpacing = (-0.3).sp)
    val title = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium)
    val body = TextStyle(fontSize = 16.sp, lineHeight = 24.sp)
    val bodySm = TextStyle(fontSize = 14.sp, lineHeight = 21.sp)
    val caption = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium)
    val label = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium, letterSpacing = 1.5.sp)
    val button = TextStyle(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium)
    val code = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 17.sp)

    val controlShape = RoundedCornerShape(8.dp)
    val cardShape = RoundedCornerShape(12.dp)
    val pillShape = RoundedCornerShape(50)
}

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val c = if (dark) Dark else Light
    // Material is still underneath for dialogs and the text cursor; point it at the same palette.
    val scheme = if (dark) {
        darkColorScheme(primary = c.primary, background = c.canvas, surface = c.card, error = c.error)
    } else {
        lightColorScheme(primary = c.primary, background = c.canvas, surface = c.card, error = c.error)
    }
    CompositionLocalProvider(LocalApp provides c) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
