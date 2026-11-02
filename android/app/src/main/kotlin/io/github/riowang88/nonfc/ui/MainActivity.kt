package io.github.riowang88.nonfc.ui

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.riowang88.nonfc.device.Engine
import io.github.riowang88.nonfc.device.Stage
import io.github.riowang88.nonfc.device.UiState

/** One screen, laid out as an iOS grouped table: status, Wi-Fi, region, steps, log. */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val engine = Engine.get(this)
        setContent {
            IosTheme {
                Box(Modifier.fillMaxSize().background(Ios.colors.background)) {
                    val state by engine.ui.collectAsStateWithLifecycle()
                    Screen(engine, state)
                }
            }
        }
    }
}

private val PERMISSIONS = buildList {
    if (Build.VERSION.SDK_INT >= 31) {
        add(Manifest.permission.BLUETOOTH_SCAN)
        add(Manifest.permission.BLUETOOTH_CONNECT)
    }
    add(Manifest.permission.ACCESS_COARSE_LOCATION)
    add(Manifest.permission.ACCESS_FINE_LOCATION)
    if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
}.toTypedArray()

/** A destructive step waiting on the action sheet's confirmation. */
private class Confirm(val title: String, val message: String, val action: String, val run: () -> Unit)

@Composable
private fun Screen(engine: Engine, state: UiState) {
    var ssid by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var region by remember { mutableStateOf(engine.region) }
    var confirm by remember { mutableStateOf<Confirm?>(null) }
    val busy = state.busy != null
    val context = LocalContext.current

    var networks by remember { mutableStateOf(emptyList<Pair<String, Boolean>>()) }
    val permissions = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        if (ssid.isEmpty()) ssid = engine.currentSsid().orEmpty()
        networks = engine.nearbyNetworks()
    }
    LaunchedEffect(Unit) { permissions.launch(PERMISSIONS) }

    val exportKey = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri ->
        if (uri != null) {
            context.contentResolver.openOutputStream(uri)?.use { it.write(engine.privateKey) }
        }
    }

    // Leaving mid-write is what the plan's section 9 forbids. The service would survive
    // it, but the person holding the phone should not think the job is over.
    BackHandler(enabled = busy) {}

    Column(
        Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(28.dp),
    ) {
        Column(Modifier.padding(top = 20.dp)) {
            Text("T6 无 NFC", style = Ios.largeTitle, color = Ios.colors.label)
            Text("让无 NFC 标签的猫砂盒照常工作", style = Ios.subhead, color = Ios.colors.secondary)
        }

        StatusCard(state)

        if (state.stage == Stage.IDLE) {
            Section(header = "Wi-Fi", footer = "T6 只支持 2.4 GHz。密码只用于这一次配网，不会保存。") {
                FieldRow("名称", ssid, { ssid = it }, "手机当前连接的网络")
                RowDivider()
                FieldRow(
                    "密码", password, { password = it }, "必填",
                    visualTransformation = PasswordVisualTransformation(),
                )
            }
            if (networks.isNotEmpty()) {
                Section(header = "附近的网络") {
                    networks.forEachIndexed { i, (name, has24) ->
                        if (i > 0) RowDivider()
                        ListRow(
                            name,
                            titleColor = if (has24) Ios.colors.label else Ios.colors.secondary,
                            subtitle = if (has24) null else "仅 5 GHz，T6 连不上",
                            trailing = {
                                if (ssid == name) Text("✓", style = Ios.headline, color = Ios.colors.blue)
                            },
                            onClick = { ssid = name },
                        )
                    }
                }
            }
            Section(header = "配网", footer = "长按 T6 按键进入配网模式，手机放在旁边。") {
                ListRow(
                    if (state.scanning) "正在扫描…" else "扫描配网模式的设备",
                    icon = Icons.Filled.Search,
                    iconTint = Ios.colors.blue,
                    titleColor = Ios.colors.blue,
                    enabled = !busy && !state.scanning,
                    trailing = { if (state.scanning) Spinner() },
                    onClick = { engine.scan() },
                )
                val ready = ssid.isNotEmpty() && password.isNotEmpty()
                state.found.forEach { device ->
                    RowDivider(inset = 59.dp)
                    ListRow(
                        device.address,
                        subtitle = if (ready) "配网到这台手机" else "先填好 Wi-Fi 名称和密码",
                        icon = Icons.Filled.Send,
                        iconTint = Ios.colors.green,
                        enabled = !busy && ready,
                        chevron = true,
                        onClick = { engine.provision(device, ssid, password) },
                    )
                }
            }
        }

        Section(header = "官方区域", footer = "交还官方时设备会回到这里的服务器，要和官方 App 账号的区域一致。") {
            Box(Modifier.padding(12.dp)) {
                SegmentedControl(
                    Engine.REGIONS, Engine.REGIONS.firstOrNull { it.url == region } ?: Engine.REGIONS.first(),
                    label = { it.label },
                    onSelect = {
                        region = it.url
                        engine.setRegion(it.url)
                    },
                )
            }
        }

        val onPhone = state.stage == Stage.DEVICE_ON_PHONE || state.stage == Stage.PATCHED
        Section(header = "步骤") {
            ListRow(
                "只读探针", subtitle = "读设备信息，不写入",
                icon = Icons.Filled.Search, iconTint = Ios.colors.teal,
                enabled = !busy && onPhone, chevron = true,
                onClick = { engine.probe() },
            )
            RowDivider(inset = 59.dp)
            ListRow(
                "打补丁", subtitle = "写入无 NFC 补丁和 SSH",
                icon = Icons.Filled.Build, iconTint = Ios.colors.blue,
                enabled = !busy && onPhone, chevron = true,
                onClick = { engine.install() },
            )
            RowDivider(inset = 59.dp)
            ListRow(
                "复查", subtitle = "设备重启回来后确认补丁还在",
                icon = Icons.Filled.CheckCircle, iconTint = Ios.colors.green,
                enabled = !busy && state.stage == Stage.PATCHED, chevron = true,
                onClick = { engine.verify() },
            )
            RowDivider(inset = 59.dp)
            ListRow(
                "交还官方", subtitle = "把设备指回官方服务器",
                icon = Icons.Filled.Send, iconTint = Ios.colors.indigo,
                enabled = !busy && onPhone, chevron = true,
                onClick = {
                    confirm = Confirm(
                        "交还官方？", "设备会离开这台手机，连到所选区域的官方服务器。", "交还",
                    ) { engine.handBack() }
                },
            )
        }

        Section(header = "维护") {
            ListRow(
                "导出 SSH 私钥", icon = Icons.Filled.Lock, iconTint = Ios.colors.gray,
                chevron = true, onClick = { exportKey.launch("t6-nonfc-ssh-key") },
            )
            RowDivider(inset = 59.dp)
            ListRow(
                "移除补丁", icon = Icons.Filled.Delete, iconTint = Ios.colors.orange,
                titleColor = Ios.colors.red, enabled = !busy,
                onClick = {
                    confirm = Confirm("移除补丁？", "设备恢复原厂程序，SSH 保留。", "移除") { engine.remove(false) }
                },
            )
            RowDivider(inset = 59.dp)
            ListRow(
                "移除补丁和 SSH", icon = Icons.Filled.Delete, iconTint = Ios.colors.red,
                titleColor = Ios.colors.red, enabled = !busy,
                onClick = {
                    confirm = Confirm(
                        "移除补丁和 SSH？", "设备恢复原厂程序，之后无法再用这把钥匙登录。", "全部移除",
                    ) { engine.remove(true) }
                },
            )
            RowDivider(inset = 59.dp)
            ListRow(
                "清除会话", icon = Icons.Filled.Refresh, iconTint = Ios.colors.gray,
                titleColor = Ios.colors.red, enabled = !busy,
                onClick = {
                    confirm = Confirm(
                        "清除会话？", "只清掉本机的记录，设备本身不受影响。", "清除",
                    ) { engine.reset() }
                },
            )
        }

        LogSection(state.log)
        Spacer(Modifier.height(12.dp))
    }

    confirm?.let { c ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            shape = RoundedCornerShape(14.dp),
            containerColor = Ios.colors.card,
            title = {
                Text(c.title, style = Ios.headline, color = Ios.colors.label, textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth())
            },
            text = {
                Text(c.message, style = Ios.footnote, color = Ios.colors.label, textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth())
            },
            confirmButton = {
                TextButton({ confirm = null; c.run() }) {
                    Text(c.action, style = Ios.headline, color = Ios.colors.red)
                }
            },
            dismissButton = {
                TextButton({ confirm = null }) { Text("取消", style = Ios.body, color = Ios.colors.blue) }
            },
        )
    }
}

private val STEPS = listOf(Stage.IDLE to "配网", Stage.DEVICE_ON_PHONE to "已接管", Stage.PATCHED to "已打补丁", Stage.HANDED_BACK to "已交还")

private fun nextHint(stage: Stage) = when (stage) {
    Stage.IDLE -> "让 T6 进入配网模式，填好 Wi-Fi，再扫描。"
    Stage.DEVICE_ON_PHONE -> "设备已指向本机。先跑只读探针，再打补丁。"
    Stage.PATCHED -> "等设备重启回到手机后复查，然后交还官方。"
    Stage.HANDED_BACK -> "到官方 App 确认设备在线，并关闭自动升级。"
}

@Composable
private fun StatusCard(state: UiState) {
    val c = Ios.colors
    val online = state.secondsSinceHeartbeat?.let { it < 90 } == true
    Column(Modifier.fillMaxWidth().clip(Ios.cardShape).background(c.card).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(STEPS.first { it.first == state.stage }.second, style = Ios.title3, color = c.label,
                modifier = Modifier.weight(1f))
            when {
                state.deviceIp == null -> StatusPill("未连接", c.gray)
                // Handed back, the device talks to PetKit now: silence here is the expected state.
                state.stage == Stage.HANDED_BACK -> StatusPill("已离开本机", c.gray)
                online -> StatusPill("在线", c.green)
                else -> StatusPill("等待心跳", c.orange)
            }
        }
        Spacer(Modifier.height(14.dp))
        StepTrack(state.stage)
        Spacer(Modifier.height(14.dp))
        Text(nextHint(state.stage), style = Ios.subhead, color = c.secondary)

        AnimatedVisibility(state.busy != null) {
            Row(
                Modifier.padding(top = 14.dp).fillMaxWidth().clip(RoundedCornerShape(10.dp))
                    .background(c.orange.copy(alpha = 0.14f)).padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Spinner(c.orange)
                Spacer(Modifier.width(10.dp))
                Text("正在${state.busy.orEmpty()}，请不要退出", style = Ios.subhead.copy(fontWeight = FontWeight.Medium),
                    color = c.orange)
            }
        }

        Spacer(Modifier.height(14.dp))
        Box(Modifier.fillMaxWidth().height(0.5.dp).background(c.separator))
        Spacer(Modifier.height(10.dp))
        InfoLine("手机", state.phoneIp ?: "—")
        InfoLine("设备", state.deviceIp ?: "—")
        if (state.identity.isNotEmpty()) InfoLine("型号", state.identity)
        InfoLine("心跳", state.secondsSinceHeartbeat?.let { "$it 秒前" } ?: "还没有")
    }
}

/** Four dots joined by a line, filled up to the current stage, like an order tracker. */
@Composable
private fun StepTrack(stage: Stage) {
    val c = Ios.colors
    val at = STEPS.indexOfFirst { it.first == stage }
    Row(verticalAlignment = Alignment.Top) {
        STEPS.forEachIndexed { i, (_, label) ->
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f).height(2.dp).background(if (i == 0) Color.Transparent else if (i <= at) c.blue else c.fill))
                    Box(
                        Modifier.size(if (i == at) 14.dp else 10.dp).clip(RoundedCornerShape(50))
                            .background(if (i <= at) c.blue else c.fill),
                    )
                    Box(Modifier.weight(1f).height(2.dp).background(if (i == STEPS.lastIndex) Color.Transparent else if (i < at) c.blue else c.fill))
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    label,
                    style = Ios.caption.copy(fontWeight = if (i == at) FontWeight.SemiBold else FontWeight.Normal),
                    color = if (i <= at) c.label else c.secondary,
                )
            }
        }
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(label, style = Ios.subhead, color = Ios.colors.secondary, modifier = Modifier.width(56.dp))
        Text(value, style = Ios.subhead.copy(fontFamily = FontFamily.Monospace), color = Ios.colors.label)
    }
}

@Composable
private fun Spinner(color: Color = Ios.colors.gray) {
    CircularProgressIndicator(Modifier.size(16.dp), color = color, strokeWidth = 2.dp)
}

@Composable
private fun LogSection(log: List<String>) {
    val clipboard = LocalClipboardManager.current
    val scroll = rememberScrollState()
    LaunchedEffect(log.size) { scroll.animateScrollTo(scroll.maxValue) }
    Column {
        Row(Modifier.padding(start = 16.dp, end = 4.dp, bottom = 7.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("日志", style = Ios.footnote, color = Ios.colors.secondary, modifier = Modifier.weight(1f))
            Text(
                "拷贝",
                style = Ios.footnote.copy(fontWeight = FontWeight.Medium),
                color = Ios.colors.blue,
                modifier = Modifier.clickable { clipboard.setText(AnnotatedString(log.joinToString("\n"))) },
            )
        }
        Box(
            Modifier.fillMaxWidth().clip(Ios.cardShape).background(Ios.colors.card)
                .heightIn(min = 60.dp, max = 320.dp).verticalScroll(scroll).padding(12.dp),
        ) {
            SelectionContainer {
                Text(
                    log.takeLast(200).joinToString("\n").ifEmpty { "还没有日志" },
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                    color = if (log.isEmpty()) Ios.colors.tertiary else Ios.colors.label,
                )
            }
        }
    }
}
