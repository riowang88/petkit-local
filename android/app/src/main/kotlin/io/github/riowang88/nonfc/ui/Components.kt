package io.github.riowang88.nonfc.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** The one coral button of a screen. It darkens while held; nothing else about it moves. */
@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
) {
    val c = App.colors
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val bg = when {
        !enabled -> c.primaryDisabled
        pressed -> c.primaryActive
        else -> c.primary
    }
    Row(
        modifier.fillMaxWidth().height(48.dp).clip(App.controlShape).background(bg)
            .clickable(source, indication = null, enabled = enabled, onClick = onClick),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (loading) {
            Spinner(c.muted)
            Spacer(Modifier.width(10.dp))
        }
        Text(text, style = App.button, color = if (enabled) c.onPrimary else c.muted)
    }
}

/** The quieter button: canvas with a hairline, for the step that is allowed but not next. */
@Composable
fun SecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    color: Color = App.colors.ink,
) {
    val c = App.colors
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    Box(
        modifier.fillMaxWidth().height(48.dp).clip(App.controlShape)
            .background(if (pressed) c.cardStrong else c.canvas)
            .border(1.dp, c.hairline, App.controlShape)
            .clickable(source, indication = null, enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = App.button, color = if (enabled) color else c.mutedSoft)
    }
}

/** A cream block one step darker than the canvas. */
@Composable
fun Panel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth().clip(App.cardShape).background(App.colors.card), content = content)
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(text, style = App.label, color = App.colors.muted, modifier = modifier.padding(start = 4.dp))
}

@Composable
fun Note(text: String, modifier: Modifier = Modifier) {
    Text(text, style = App.bodySm, color = App.colors.muted, modifier = modifier.padding(horizontal = 4.dp))
}

@Composable
fun Divider() {
    Box(Modifier.padding(horizontal = 16.dp).fillMaxWidth().height(1.dp).background(App.colors.divider))
}

/** One tappable line inside a [Panel]. Destructive ones are told apart by text color alone. */
@Composable
fun ActionRow(
    title: String,
    onClick: () -> Unit,
    subtitle: String? = null,
    enabled: Boolean = true,
    danger: Boolean = false,
    leading: (@Composable () -> Unit)? = null,
) {
    val c = App.colors
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val bg by animateColorAsState(if (pressed) c.cardStrong else c.card, label = "row")
    Row(
        Modifier.fillMaxWidth().background(bg)
            .clickable(source, indication = null, enabled = enabled, onClick = onClick)
            .heightIn(min = 52.dp).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            leading()
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                title, style = App.title,
                color = when {
                    !enabled -> c.mutedSoft
                    danger -> c.error
                    else -> c.ink
                },
            )
            if (subtitle != null) Text(subtitle, style = App.bodySm, color = c.muted)
        }
        Spacer(Modifier.width(8.dp))
        Text("›", style = App.displayMd, color = if (enabled) c.mutedSoft else c.divider)
    }
}

/** A labelled input. The border turns coral while it has focus. */
@Composable
fun Field(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    visualTransformation: VisualTransformation = VisualTransformation.None,
) {
    val c = App.colors
    var focused by remember { mutableStateOf(false) }
    Column(modifier.fillMaxWidth()) {
        Text(label, style = App.caption, color = c.muted)
        Spacer(Modifier.height(6.dp))
        Box(
            Modifier.fillMaxWidth().height(48.dp).clip(App.controlShape).background(c.canvas)
                .border(1.dp, if (focused) c.primary else c.hairline, App.controlShape)
                .padding(horizontal = 14.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            if (value.isEmpty()) {
                Text(placeholder, style = App.body, color = c.mutedSoft, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            BasicTextField(
                value, onValueChange,
                Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused },
                singleLine = true,
                textStyle = App.body.copy(color = c.ink),
                cursorBrush = SolidColor(c.primary),
                visualTransformation = visualTransformation,
            )
        }
    }
}

/** A row of choices on a darker track, the chosen one lifted to canvas. */
@Composable
fun <T> Segmented(
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = App.colors
    Row(
        modifier.fillMaxWidth().clip(App.controlShape).background(c.cardStrong).padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        options.forEach { option ->
            val on = option == selected
            val bg by animateColorAsState(if (on) c.canvas else Color.Transparent, label = "segment")
            Box(
                Modifier.weight(1f).height(36.dp).clip(App.controlShape).background(bg)
                    .clickable(remember { MutableInteractionSource() }, indication = null) { onSelect(option) },
                contentAlignment = Alignment.Center,
            ) {
                Text(label(option), style = App.caption, color = if (on) c.ink else c.muted, maxLines = 1)
            }
        }
    }
}

/** A small pill: a colored dot for the state, the word itself in ink. */
@Composable
fun StatusPill(text: String, dot: Color) {
    Row(
        Modifier.clip(App.pillShape).background(App.colors.card).padding(horizontal = 12.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(dot))
        Spacer(Modifier.width(7.dp))
        Text(text, style = App.caption, color = App.colors.ink)
    }
}

/** A selectable pill, used for the nearby networks. */
@Composable
fun Chip(text: String, selected: Boolean, onClick: () -> Unit) {
    val c = App.colors
    Box(
        Modifier.clip(App.pillShape).background(if (selected) c.ink else c.canvas)
            .border(1.dp, if (selected) c.ink else c.hairline, App.pillShape)
            .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(
            text, style = App.caption, maxLines = 1,
            color = if (selected) c.canvas else c.ink,
        )
    }
}

/** Numbered stops joined by a line, filled in ink up to the current one. */
@Composable
fun StepTrack(labels: List<String>, at: Int, modifier: Modifier = Modifier) {
    val c = App.colors
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        labels.forEachIndexed { i, label ->
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val left = if (i == 0) Color.Transparent else if (i <= at) c.ink else c.cardStrong
                    val right = if (i == labels.lastIndex) Color.Transparent else if (i < at) c.ink else c.cardStrong
                    Box(Modifier.weight(1f).height(1.dp).background(left))
                    Box(
                        Modifier.size(22.dp).clip(CircleShape).background(if (i <= at) c.ink else c.cardStrong),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("${i + 1}", style = App.label.copy(letterSpacing = App.body.letterSpacing),
                            color = if (i <= at) c.canvas else c.muted)
                    }
                    Box(Modifier.weight(1f).height(1.dp).background(right))
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    label,
                    style = App.caption.copy(fontWeight = if (i == at) FontWeight.Medium else FontWeight.Normal),
                    color = if (i <= at) c.ink else c.mutedSoft,
                )
            }
        }
    }
}

@Composable
fun Spinner(color: Color = App.colors.muted) {
    CircularProgressIndicator(Modifier.size(16.dp), color = color, strokeWidth = 2.dp)
}

/** The log as a terminal window: the one dark surface on the page. Tap the bar to grow it. */
@Composable
fun LogCard(log: List<String>, modifier: Modifier = Modifier) {
    val c = App.colors
    val clipboard = LocalClipboardManager.current
    val scroll = rememberScrollState()
    var tall by remember { mutableStateOf(false) }
    LaunchedEffect(log.size, tall) { scroll.animateScrollTo(scroll.maxValue) }
    Column(modifier.fillMaxWidth().clip(App.cardShape).background(c.log)) {
        Row(
            Modifier.fillMaxWidth()
                .clickable(remember { MutableInteractionSource() }, indication = null) { tall = !tall }
                .padding(start = 16.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("日志", style = App.label, color = c.onLogSoft, modifier = Modifier.weight(1f))
            Text(if (tall) "收起" else "展开", style = App.caption, color = c.onLogSoft,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp))
            Text(
                "拷贝", style = App.caption, color = c.onLog,
                modifier = Modifier
                    .clickable(remember { MutableInteractionSource() }, indication = null) {
                        clipboard.setText(AnnotatedString(log.joinToString("\n")))
                    }
                    .padding(horizontal = 12.dp, vertical = 12.dp),
            )
        }
        Box(
            Modifier.fillMaxWidth().heightIn(min = 72.dp, max = if (tall) 440.dp else 180.dp)
                .verticalScroll(scroll).padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
        ) {
            SelectionContainer {
                Text(
                    log.takeLast(200).joinToString("\n").ifEmpty { "还没有日志" },
                    style = App.code,
                    color = if (log.isEmpty()) c.onLogSoft else c.onLog,
                )
            }
        }
    }
}

/** The two destinations, as tabs along the bottom edge. */
@Composable
fun BottomTabs(labels: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    val c = App.colors
    Column(Modifier.fillMaxWidth().background(c.canvas)) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(c.hairline))
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            labels.forEachIndexed { i, label ->
                val on = i == selected
                Box(
                    Modifier.weight(1f).height(40.dp).clip(App.controlShape)
                        .background(if (on) c.card else Color.Transparent)
                        .clickable(remember { MutableInteractionSource() }, indication = null) { onSelect(i) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(label, style = App.button, color = if (on) c.ink else c.muted)
                }
            }
        }
    }
}
