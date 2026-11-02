package io.github.riowang88.nonfc.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The iOS look, drawn with plain Compose: grouped inset lists on systemGroupedBackground,
 * the system colors of UIKit in both appearances, and press feedback as a dimmed row
 * rather than Material's ripple. SF Pro is not redistributable, so text is the phone's
 * own sans at SF's sizes and weights.
 */
@Immutable
data class IosColors(
    val background: Color,
    val card: Color,
    val separator: Color,
    val label: Color,
    val secondary: Color,
    val tertiary: Color,
    val fill: Color,
    val pressed: Color,
    val raised: Color,
    val blue: Color,
    val green: Color,
    val orange: Color,
    val red: Color,
    val gray: Color,
    val indigo: Color,
    val teal: Color,
)

private val Light = IosColors(
    background = Color(0xFFF2F2F7),
    card = Color.White,
    separator = Color(0x4A3C3C43),
    label = Color.Black,
    secondary = Color(0x993C3C43),
    tertiary = Color(0x4D3C3C43),
    fill = Color(0x1F767680),
    pressed = Color(0xFFD1D1D6),
    raised = Color.White,
    blue = Color(0xFF007AFF),
    green = Color(0xFF34C759),
    orange = Color(0xFFFF9500),
    red = Color(0xFFFF3B30),
    gray = Color(0xFF8E8E93),
    indigo = Color(0xFF5856D6),
    teal = Color(0xFF30B0C7),
)

private val Dark = IosColors(
    background = Color.Black,
    card = Color(0xFF1C1C1E),
    separator = Color(0x99545458),
    label = Color.White,
    secondary = Color(0x99EBEBF5),
    tertiary = Color(0x4DEBEBF5),
    fill = Color(0x3D767680),
    pressed = Color(0xFF3A3A3C),
    raised = Color(0xFF636366),
    blue = Color(0xFF0A84FF),
    green = Color(0xFF30D158),
    orange = Color(0xFFFF9F0A),
    red = Color(0xFFFF453A),
    gray = Color(0xFF8E8E93),
    indigo = Color(0xFF5E5CE6),
    teal = Color(0xFF40C8E0),
)

val LocalIos = staticCompositionLocalOf { Light }

object Ios {
    val colors: IosColors @Composable get() = LocalIos.current
    val largeTitle = TextStyle(fontSize = 34.sp, lineHeight = 41.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.4.sp)
    val title3 = TextStyle(fontSize = 20.sp, lineHeight = 25.sp, fontWeight = FontWeight.SemiBold)
    val headline = TextStyle(fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold)
    val body = TextStyle(fontSize = 17.sp, lineHeight = 22.sp)
    val subhead = TextStyle(fontSize = 15.sp, lineHeight = 20.sp)
    val footnote = TextStyle(fontSize = 13.sp, lineHeight = 18.sp)
    val caption = TextStyle(fontSize = 12.sp, lineHeight = 16.sp)
    val cardShape = RoundedCornerShape(10.dp)
}

@Composable
fun IosTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val c = if (dark) Dark else Light
    // Material is still underneath for dialogs and the text cursor; point it at the same palette.
    val scheme = if (dark) {
        darkColorScheme(primary = c.blue, background = c.background, surface = c.card, error = c.red)
    } else {
        lightColorScheme(primary = c.blue, background = c.background, surface = c.card, error = c.red)
    }
    CompositionLocalProvider(LocalIos provides c) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}

/** An inset grouped section: small-caps header, rounded card, optional footer. */
@Composable
fun Section(
    header: String? = null,
    footer: String? = null,
    content: @Composable () -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        if (header != null) {
            Text(
                header.uppercase(),
                style = Ios.footnote,
                color = Ios.colors.secondary,
                modifier = Modifier.padding(start = 16.dp, bottom = 7.dp),
            )
        }
        Column(Modifier.fillMaxWidth().clip(Ios.cardShape).background(Ios.colors.card)) { content() }
        if (footer != null) {
            Text(
                footer,
                style = Ios.footnote,
                color = Ios.colors.secondary,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 7.dp),
            )
        }
    }
}

/** The hairline between rows, inset to where the text starts. */
@Composable
fun RowDivider(inset: Dp = 16.dp) {
    Box(Modifier.padding(start = inset).fillMaxWidth().height(0.5.dp).background(Ios.colors.separator))
}

/** The rounded-square glyph of a Settings row. */
@Composable
fun IconBadge(icon: ImageVector, tint: Color) {
    Box(
        Modifier.size(29.dp).clip(RoundedCornerShape(7.dp)).background(tint),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, tint = Color.White, modifier = Modifier.size(18.dp))
    }
}

/**
 * One table row. With [onClick] it highlights while held, as a UITableViewCell does;
 * disabled, its title goes to tertiary label and it ignores touches.
 */
@Composable
fun ListRow(
    title: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    iconTint: Color = Ios.colors.blue,
    titleColor: Color = Ios.colors.label,
    value: String? = null,
    subtitle: String? = null,
    enabled: Boolean = true,
    chevron: Boolean = false,
    trailing: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val bg by animateColorAsState(if (pressed) Ios.colors.pressed else Ios.colors.card, label = "row")
    val clickable = if (onClick != null) {
        Modifier.clickable(source, indication = null, enabled = enabled, onClick = onClick)
    } else {
        Modifier
    }
    Row(
        modifier.fillMaxWidth().background(bg).then(clickable).heightIn(min = 44.dp)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            IconBadge(icon, if (enabled) iconTint else Ios.colors.gray.copy(alpha = 0.5f))
            Spacer(Modifier.width(14.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = Ios.body, color = if (enabled) titleColor else Ios.colors.tertiary)
            if (subtitle != null) {
                Text(subtitle, style = Ios.footnote, color = Ios.colors.secondary)
            }
        }
        if (value != null) {
            Text(value, style = Ios.body, color = Ios.colors.secondary, maxLines = 1)
        }
        trailing?.invoke()
        if (chevron) {
            Spacer(Modifier.width(8.dp))
            Text("›", style = Ios.title3.copy(fontWeight = FontWeight.Normal), color = Ios.colors.tertiary)
        }
    }
}

/** A text field laid into a row, label on the left as in Settings' Wi-Fi password sheet. */
@Composable
fun FieldRow(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    visualTransformation: VisualTransformation = VisualTransformation.None,
) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 44.dp).padding(horizontal = 16.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = Ios.body, color = Ios.colors.label, modifier = Modifier.width(72.dp))
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) Text(placeholder, style = Ios.body, color = Ios.colors.tertiary, maxLines = 1)
            BasicTextField(
                value, onValueChange,
                Modifier.fillMaxWidth(),
                singleLine = true,
                textStyle = Ios.body.copy(color = Ios.colors.label),
                cursorBrush = SolidColor(Ios.colors.blue),
                visualTransformation = visualTransformation,
            )
        }
    }
}

/** UISegmentedControl: a gray track with the chosen segment raised in white. */
@Composable
fun <T> SegmentedControl(
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(9.dp)).background(Ios.colors.fill).padding(2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        options.forEach { option ->
            val on = option == selected
            val bg by animateColorAsState(
                if (on) Ios.colors.raised else Color.Transparent,
                label = "segment",
            )
            Box(
                Modifier.weight(1f).height(30.dp).clip(RoundedCornerShape(7.dp)).background(bg)
                    .clickable(remember { MutableInteractionSource() }, indication = null) { onSelect(option) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label(option),
                    style = Ios.footnote.copy(fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium),
                    color = Ios.colors.label,
                    maxLines = 1,
                )
            }
        }
    }
}

/** A filled capsule-cornered button, the prominent style of iOS 15+. */
@Composable
fun FilledButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    color: Color = Ios.colors.blue,
) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val bg = when {
        !enabled -> Ios.colors.fill
        pressed -> color.copy(alpha = 0.7f)
        else -> color
    }
    Box(
        modifier.fillMaxWidth().height(50.dp).clip(RoundedCornerShape(12.dp)).background(bg)
            .clickable(source, indication = null, enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = Ios.headline, color = if (enabled) Color.White else Ios.colors.tertiary)
    }
}

/** The small colored dot and word used for a status, like Find My's "Online". */
@Composable
fun StatusPill(text: String, color: Color) {
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(color.copy(alpha = 0.15f))
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(7.dp).clip(RoundedCornerShape(50)).background(color))
        Spacer(Modifier.width(6.dp))
        Text(text, style = Ios.footnote.copy(fontWeight = FontWeight.SemiBold), color = color)
    }
}
