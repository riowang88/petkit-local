package io.github.riowang88.nonfc.ui

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.riowang88.nonfc.device.Engine
import io.github.riowang88.nonfc.device.Stage
import io.github.riowang88.nonfc.device.UiState

/** The debug screen of stages 1 to 5: every step is a button, and the log is the result. */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val engine = Engine.get(this)
        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
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
    } else {
        add(Manifest.permission.ACCESS_COARSE_LOCATION)
        add(Manifest.permission.ACCESS_FINE_LOCATION)
    }
    if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
}.toTypedArray()

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Screen(engine: Engine, state: UiState) {
    var ssid by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var region by remember { mutableStateOf(engine.region) }
    val busy = state.busy != null
    val context = androidx.compose.ui.platform.LocalContext.current

    val permissions = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {}
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
        Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("T6 无 NFC 工具", style = MaterialTheme.typography.headlineMedium)
        Text(
            "阶段 ${state.stage}\n手机 ${state.phoneIp ?: "-"}  设备 ${state.deviceIp ?: "-"}  " +
                "${state.identity}\n心跳 ${state.secondsSinceHeartbeat?.let { "$it 秒前" } ?: "还没有"}" +
                (state.busy?.let { "\n正在：$it（不要退出）" } ?: ""),
        )

        if (state.stage == Stage.IDLE) {
            OutlinedTextField(ssid, { ssid = it }, Modifier.fillMaxWidth(), label = { Text("Wi-Fi 名称") })
            OutlinedTextField(
                password, { password = it }, Modifier.fillMaxWidth(),
                label = { Text("Wi-Fi 密码") },
                visualTransformation = PasswordVisualTransformation(),
            )
            Button({ engine.scan() }, enabled = !busy) { Text("扫描配网模式的设备") }
            state.found.forEach { device ->
                OutlinedButton(
                    { engine.provision(device, ssid, password) },
                    Modifier.fillMaxWidth(),
                    enabled = !busy && ssid.isNotEmpty() && password.isNotEmpty(),
                ) { Text("配网到这台手机：${device.address}") }
            }
        }

        Text("官方区域（交还用）")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Engine.REGIONS.forEach {
                FilterChip(
                    selected = region == it.url,
                    onClick = {
                        region = it.url
                        engine.setRegion(it.url)
                    },
                    label = { Text(it.label) },
                )
            }
        }

        val onPhone = state.stage == Stage.DEVICE_ON_PHONE || state.stage == Stage.PATCHED
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button({ engine.probe() }, enabled = !busy && onPhone) { Text("只读探针") }
            Button({ engine.install() }, enabled = !busy && onPhone) { Text("打补丁") }
            Button({ engine.verify() }, enabled = !busy && state.stage == Stage.PATCHED) { Text("复查") }
            Button({ engine.handBack() }, enabled = !busy && onPhone) { Text("交还官方") }
            OutlinedButton({ engine.remove(false) }, enabled = !busy) { Text("移除补丁") }
            OutlinedButton({ engine.remove(true) }, enabled = !busy) { Text("移除补丁和 SSH") }
            OutlinedButton({ exportKey.launch("t6-nonfc-ssh-key") }) { Text("导出 SSH 私钥") }
            OutlinedButton({ engine.reset() }, enabled = !busy) { Text("清除会话") }
        }

        SelectionContainer {
            Text(
                state.log.takeLast(200).joinToString("\n"),
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
            )
        }
    }
}
