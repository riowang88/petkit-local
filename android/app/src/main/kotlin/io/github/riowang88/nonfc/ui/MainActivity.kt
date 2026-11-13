package io.github.riowang88.nonfc.ui

import android.Manifest
import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.drawable.Animatable
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Bundle
import android.widget.ImageView
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.riowang88.nonfc.device.Engine
import io.github.riowang88.nonfc.device.Nearby
import io.github.riowang88.nonfc.device.Stage
import io.github.riowang88.nonfc.device.UiState
import kotlinx.coroutines.delay

/**
 * Two tabs under a header that never leaves. "流程" shows only what the current stage
 * needs, with one coral button for the next step; "工具" keeps the rescue and removal
 * actions out of the way of someone who is just following the steps.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val engine = Engine.get(this)
        setContent {
            AppTheme {
                Box(Modifier.fillMaxSize().background(App.colors.canvas)) {
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

/** A destructive step waiting on the dialog's confirmation. */
private class Confirm(val title: String, val message: String, val action: String, val run: () -> Unit)

private val TABS = listOf("流程", "工具")

@Composable
private fun Screen(engine: Engine, state: UiState) {
    // Held here rather than in a tab, so switching tabs loses nothing that was typed.
    var ssid by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var region by remember { mutableStateOf(engine.region) }
    var confirm by remember { mutableStateOf<Confirm?>(null) }
    var rescueIp by remember(state.deviceIp) { mutableStateOf(state.deviceIp.orEmpty()) }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    // One per tab: a shared one would open "流程" scrolled to wherever "工具" was left.
    val scrolls = listOf(rememberScrollState(), rememberScrollState())
    val busy = state.busy != null
    val context = LocalContext.current

    var networks by remember { mutableStateOf(emptyList<String>()) }
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

    val setRegion = { url: String ->
        region = url
        engine.setRegion(url)
    }
    val handBack = {
        confirm = Confirm(
            "交还官方？", "设备会离开这台手机，连到所选区域的官方服务器。", "交还",
        ) { engine.handBack() }
    }
    val clearSession = {
        confirm = Confirm("清除会话？", "只清掉本机的记录，设备本身不受影响。", "清除") { engine.reset() }
    }

    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        Header(state)
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(scrolls[tab])
                .padding(horizontal = 20.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (tab == 0) {
                if (!busy) state.result?.let { Outcome(it) }
                when (state.stage) {
                    Stage.IDLE -> Provision(
                        engine, state, ssid, { ssid = it }, password, { password = it }, networks,
                    )
                    Stage.DEVICE_ON_PHONE, Stage.PATCHED -> OnPhone(engine, state, region, setRegion, handBack)
                    Stage.HANDED_BACK -> HandedBack(busy, clearSession)
                }
                LogCard(state.log)
            } else {
                Tools(
                    engine, state, region, setRegion, rescueIp, { rescueIp = it },
                    ask = { confirm = it },
                    exportKey = { exportKey.launch("t6-nonfc-ssh-key") },
                    clearSession = clearSession,
                )
            }
        }
        BottomTabs(TABS, tab) { tab = it }
    }

    confirm?.let { c ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            shape = App.cardShape,
            containerColor = App.colors.canvas,
            title = { Text(c.title, style = App.displayMd, color = App.colors.ink) },
            text = { Text(c.message, style = App.body, color = App.colors.body) },
            confirmButton = {
                TextButton({ confirm = null; c.run() }) {
                    Text(c.action, style = App.button, color = App.colors.error)
                }
            },
            dismissButton = {
                TextButton({ confirm = null }) { Text("取消", style = App.button, color = App.colors.ink) }
            },
        )
    }
}

private val STEPS = listOf(
    Stage.IDLE to "配网", Stage.DEVICE_ON_PHONE to "已接管", Stage.PATCHED to "已打补丁", Stage.HANDED_BACK to "已交还",
)

/** Title, connection state, where the session stands, and the do-not-leave bar while working. */
@Composable
private fun Header(state: UiState) {
    val c = App.colors
    val online = state.secondsSinceHeartbeat?.let { it < 90 } == true
    Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("T6 无 NFC", style = App.displayLg, color = c.ink, modifier = Modifier.weight(1f))
            when {
                state.deviceIp == null -> StatusPill("未连接", c.mutedSoft)
                // Handed back, the device talks to PetKit now: silence here is the expected state.
                state.stage == Stage.HANDED_BACK -> StatusPill("已离开本机", c.mutedSoft)
                online -> StatusPill("在线", c.success)
                else -> StatusPill("等待心跳", c.warning)
            }
        }
        Spacer(Modifier.height(16.dp))
        StepTrack(STEPS.map { it.second }, STEPS.indexOfFirst { it.first == state.stage })
        AnimatedVisibility(state.busy != null) {
            // Kept as last seen while it animates away, so it does not collapse to an empty box.
            var shown by remember { mutableStateOf(state) }
            if (state.busy != null) shown = state
            Progress(shown)
        }
        Spacer(Modifier.height(14.dp))
        Box(Modifier.fillMaxWidth().height(1.dp).background(c.hairline))
    }
}

/** How long each task usually takes, measured on a real T6 where a run was recorded. */
private val EXPECT = mapOf(
    "配网" to "全程通常 1–2 分钟。",
    "只读探针" to "通常半分钟内。只读，不写入。",
    "打补丁" to "全程通常 4–8 分钟，设备会重启 1–2 次，屏幕黑一下是正常的。",
    "复查" to "通常 1 分钟内。",
    "交还官方" to "全程通常 3–5 分钟，设备会重启两次。",
    "移除补丁" to "通常 2–3 分钟，设备会重启一次。",
    "检测连通" to "约 10 秒。",
    "读取启动脚本" to "通常十几秒。",
    "改名启动脚本并重启" to "十几秒，之后设备自己重启。",
)

/** 3:07 for a duration, so a count that keeps moving shows the app is not stuck. */
private fun clock(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(s / 60, s % 60)
}

/**
 * What the running task is doing, in words, with time that visibly moves. A bare spinner
 * over minutes of rebooting reads as a hung app; a phase that changes and a clock that
 * counts say it is working, and the overall estimate says how long to expect.
 */
@Composable
private fun Progress(state: UiState) {
    val c = App.colors
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(state.busySince) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }
    val task = state.busy.orEmpty()
    val inPhase = now - state.phaseSince
    Column(
        Modifier.padding(top = 14.dp).fillMaxWidth().clip(App.controlShape)
            .background(c.warning.copy(alpha = 0.16f)).padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Spinner(c.ink)
            Spacer(Modifier.width(10.dp))
            Text("正在$task", style = App.title, color = c.ink, modifier = Modifier.weight(1f))
            Text("已用 ${clock(now - state.busySince)}", style = App.caption.copy(fontFamily = FontFamily.Monospace), color = c.ink)
        }
        Spacer(Modifier.height(6.dp))
        Text(state.phase ?: "开始…", style = App.body, color = c.ink)
        // Only once a phase has gone on long enough that someone might start to wonder.
        if (state.phase != null && inPhase >= 20_000) {
            Text("这一步已等 ${clock(inPhase)}", style = App.caption, color = c.body)
        }
        Spacer(Modifier.height(6.dp))
        Text(
            listOfNotNull(EXPECT[task], "请别退出 App，也别给设备断电。").joinToString(""),
            style = App.caption, color = c.body,
        )
    }
}

/**
 * How the last task ended. Without it a failure just makes the progress bar vanish, and
 * the reason sits in the log, which is the one place a non-technical user will not look.
 */
@Composable
private fun Outcome(result: io.github.riowang88.nonfc.device.TaskResult) {
    val c = App.colors
    val tint = if (result.ok) c.success else c.error
    Column(
        Modifier.fillMaxWidth().clip(App.controlShape).background(tint.copy(alpha = 0.12f))
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Text(
            if (result.ok) "✓ ${result.task}完成" else "✗ ${result.task}没有成功",
            style = App.title, color = c.ink,
        )
        if (!result.ok) {
            if (result.message.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text(result.message, style = App.body, color = c.ink)
            }
            Spacer(Modifier.height(4.dp))
            Text("可以直接再试一次。还不行，点下方日志的「拷贝」发给开发者。", style = App.caption, color = c.body)
        }
    }
}

/** The heading every stage opens with: what this stage is, and what to do in it. */
@Composable
private fun Lead(title: String, hint: String) {
    Column {
        Text(title, style = App.displayMd, color = App.colors.ink)
        Spacer(Modifier.height(4.dp))
        Text(hint, style = App.body, color = App.colors.body)
    }
}

/** At most this many non-PetKit rows: a busy room can hold dozens of watches and earbuds. */
private const val OTHER_ROWS = 15

@Composable
private fun Provision(
    engine: Engine,
    state: UiState,
    ssid: String,
    onSsid: (String) -> Unit,
    password: String,
    onPassword: (String) -> Unit,
    networks: List<String>,
) {
    val busy = state.busy != null
    Lead("配置网络", "先开启设备的配网，再填好 Wi-Fi 并扫描。")

    BindGuide()

    Panel {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Field("名称", ssid, onSsid, "手机当前连接的网络")
            if (networks.isNotEmpty()) {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    networks.forEach { name -> Chip(name, ssid == name, { onSsid(name) }) }
                }
            }
            Field("密码", password, onPassword, "必填", visualTransformation = PasswordVisualTransformation())
        }
    }
    Note("2.4 GHz 和 5 GHz 都可以。密码只用于这一次配网，不会保存。")

    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(state.scanning) {
        while (state.scanning) {
            now = System.currentTimeMillis()
            delay(500)
        }
    }
    val left = ((state.scanUntil - now + 999) / 1000).coerceAtLeast(0)
    PrimaryButton(
        if (state.scanning) "正在扫描附近设备… 还剩 $left 秒" else "扫描配网模式的设备",
        onClick = { engine.scan() },
        enabled = !busy && !state.scanning,
        loading = state.scanning,
    )

    val ready = ssid.isNotEmpty() && password.isNotEmpty()
    val (likely, others) = state.found.partition { it.likely }
    val shown = likely + others.take(OTHER_ROWS)
    if (shown.isNotEmpty()) {
        if (!ready) Note("先填好 Wi-Fi 名称和密码，再点设备。")
        Panel {
            shown.forEachIndexed { i, nearby ->
                if (i > 0) Divider()
                NearbyRow(nearby, enabled = !busy && ready) { engine.provision(nearby.device, ssid, password) }
            }
        }
        if (likely.isEmpty()) {
            Note("没有认出 PetKit 时，按信号最强、名字最像的试。选错不会写入任何东西。")
        }
    }
}

/** Assets file a local build may carry; see `app/local-assets/` in the Gradle script. */
private const val BIND_IMAGE = "t6_bind_start.gif"

/** The pairing picture, animated where the platform can decode an animated GIF itself. */
private fun loadBindImage(context: Context): Drawable? = runCatching {
    if (Build.VERSION.SDK_INT >= 28) {
        ImageDecoder.decodeDrawable(ImageDecoder.createSource(context.assets, BIND_IMAGE)).also {
            if (it is AnimatedImageDrawable) it.repeatCount = AnimatedImageDrawable.REPEAT_INFINITE
        }
    } else {
        context.assets.open(BIND_IMAGE).use(BitmapFactory::decodeStream)?.let { BitmapDrawable(context.resources, it) }
    }
}.getOrNull()?.takeIf { it.intrinsicWidth > 0 && it.intrinsicHeight > 0 }

/** How to put the T6 in pairing mode, which has to happen before anything else here can. */
@Composable
private fun BindGuide() {
    val context = LocalContext.current
    val image = remember { loadBindImage(context) }
    Panel {
        if (image != null) {
            AndroidView(
                factory = {
                    ImageView(it).apply {
                        scaleType = ImageView.ScaleType.FIT_XY
                        setImageDrawable(image)
                        (image as? Animatable)?.start()
                    }
                },
                modifier = Modifier.fillMaxWidth()
                    .aspectRatio(image.intrinsicWidth.toFloat() / image.intrinsicHeight),
            )
        }
        Column(Modifier.padding(16.dp)) {
            Text("先开启设备的配网", style = App.title, color = App.colors.ink)
            Spacer(Modifier.height(4.dp))
            Text(
                "点击设备上的菜单键，切换到「配置网络」。然后把手机放在设备旁边。",
                style = App.bodySm, color = App.colors.body,
            )
        }
    }
}

@Composable
private fun NearbyRow(nearby: Nearby, enabled: Boolean, onClick: () -> Unit) {
    ActionRow(
        nearby.name.ifEmpty { "未命名设备" },
        onClick,
        subtitle = "${nearby.device.address} · ${nearby.rssi} dBm",
        enabled = enabled,
        leading = {
            Box(
                Modifier.size(8.dp).clip(CircleShape)
                    .background(if (nearby.likely) App.colors.success else App.colors.mutedSoft),
            )
        },
    )
}

/** The device is pointed at this phone: patch it, check it, then hand it back. */
@Composable
private fun OnPhone(
    engine: Engine,
    state: UiState,
    region: String,
    setRegion: (String) -> Unit,
    handBack: () -> Unit,
) {
    val busy = state.busy != null
    if (state.stage == Stage.PATCHED) {
        Lead("补丁已写入", "等设备重启回到手机后复查，然后交还官方。")
        PrimaryButton("复查", { engine.verify() }, enabled = !busy)
        Note("设备重启回来后确认补丁还在。")
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SecondaryButton("只读探针", { engine.probe() }, Modifier.weight(1f), enabled = !busy)
            SecondaryButton("重新打补丁", { engine.install() }, Modifier.weight(1f), enabled = !busy)
        }
    } else {
        Lead("设备已接管", "设备已指向本机。先跑只读探针，再打补丁。")
        PrimaryButton("打补丁", { engine.install() }, enabled = !busy)
        Note("写入无 NFC 补丁和 SSH。")
        SecondaryButton("只读探针", { engine.probe() }, enabled = !busy)
        Note("读设备信息，不写入。")
    }

    Spacer(Modifier.height(4.dp))
    SectionLabel("交还官方")
    Panel {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            RegionPicker(region, setRegion)
            SecondaryButton("交还官方", handBack, enabled = !busy)
        }
    }
    Note("交还官方时设备会回到这里的服务器，要和官方 App 账号的区域一致。")
}

@Composable
private fun HandedBack(busy: Boolean, clearSession: () -> Unit) {
    Lead("已交还官方", "到官方 App 确认设备在线，并关闭自动升级。")
    SecondaryButton("开始新的会话", clearSession, enabled = !busy)
    Note("只清掉本机的记录，设备本身不受影响。")
}

@Composable
private fun RegionPicker(region: String, setRegion: (String) -> Unit) {
    Segmented(
        Engine.REGIONS, Engine.REGIONS.firstOrNull { it.url == region } ?: Engine.REGIONS.first(),
        label = { it.label },
        onSelect = { setRegion(it.url) },
    )
}

/** Everything that is not the next step: what the phone knows, rescue, and removal. */
@Composable
private fun Tools(
    engine: Engine,
    state: UiState,
    region: String,
    setRegion: (String) -> Unit,
    rescueIp: String,
    onRescueIp: (String) -> Unit,
    ask: (Confirm) -> Unit,
    exportKey: () -> Unit,
    clearSession: () -> Unit,
) {
    val busy = state.busy != null

    SectionLabel("设备信息")
    Panel {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            InfoLine("手机", state.phoneIp ?: "—")
            InfoLine("设备", state.deviceIp ?: "—")
            if (state.identity.isNotEmpty()) InfoLine("型号", state.identity)
            InfoLine("心跳", state.secondsSinceHeartbeat?.let { "$it 秒前" } ?: "还没有")
        }
    }

    Spacer(Modifier.height(4.dp))
    SectionLabel("官方区域")
    Panel { Box(Modifier.padding(16.dp)) { RegionPicker(region, setRegion) } }
    Note("交还官方时设备会回到这里的服务器，要和官方 App 账号的区域一致。")

    Spacer(Modifier.height(4.dp))
    SectionLabel("救援")
    Panel {
        Field("设备 IP", rescueIp, onRescueIp, "例如 192.168.1.104", Modifier.padding(16.dp))
        Divider()
        ActionRow(
            "检测连通", { engine.checkReachable(rescueIp) },
            subtitle = "ping 一下，再试 SSH 的 22 端口",
            enabled = !busy && rescueIp.isNotBlank(),
        )
        Divider()
        ActionRow(
            "读取启动脚本", { engine.rescueRead(rescueIp) },
            subtitle = "SSH 登录，只读，不改任何东西",
            enabled = !busy && rescueIp.isNotBlank(),
        )
        Divider()
        ActionRow(
            "改名启动脚本并重启",
            {
                ask(
                    Confirm(
                        "改名启动脚本并重启？",
                        "启动脚本会改名为 app_init.sh.bad.<时间> 保留下来，设备随即重启并走原厂启动。" +
                            "补丁和 SSH 下次开机不再生效。脚本本身正常时不会执行。",
                        "改名并重启",
                    ) { engine.rescueMoveAside(rescueIp) },
                )
            },
            subtitle = "让设备回到原厂启动",
            enabled = !busy && rescueIp.isNotBlank(),
            danger = true,
        )
    }
    Note(
        "设备开机后屏幕和按键都没反应时用。SSH 只认装 SSH 时用的那台手机里的钥匙：" +
            "更新 App 请直接覆盖安装，卸载会把钥匙一起删掉。",
    )

    Spacer(Modifier.height(4.dp))
    SectionLabel("维护")
    Panel {
        ActionRow("导出 SSH 私钥", exportKey)
        Divider()
        ActionRow(
            "移除补丁",
            { ask(Confirm("移除补丁？", "设备恢复原厂程序，SSH 保留。", "移除") { engine.remove(false) }) },
            enabled = !busy, danger = true,
        )
        Divider()
        ActionRow(
            "移除补丁和 SSH",
            {
                ask(
                    Confirm("移除补丁和 SSH？", "设备恢复原厂程序，之后无法再用这把钥匙登录。", "全部移除") {
                        engine.remove(true)
                    },
                )
            },
            enabled = !busy, danger = true,
        )
        Divider()
        ActionRow("清除会话", clearSession, enabled = !busy, danger = true)
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(label, style = App.bodySm, color = App.colors.muted, modifier = Modifier.width(56.dp))
        Text(value, style = App.bodySm.copy(fontFamily = FontFamily.Monospace), color = App.colors.ink)
    }
}
