package io.github.riowang88.nonfc.device

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import io.github.riowang88.nonfc.core.BootScript
import io.github.riowang88.nonfc.core.DeviceApi
import io.github.riowang88.nonfc.core.Installed
import io.github.riowang88.nonfc.core.Orchestrator
import io.github.riowang88.nonfc.core.Rescue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.Inet4Address
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Where a session stands. Written to disk on change, so a killed app can resume it. */
enum class Stage { IDLE, DEVICE_ON_PHONE, PATCHED, HANDED_BACK }

/**
 * One device the scan heard. [likely] is a PetKit name or the provisioning service in the
 * advertisement; the rest are listed too, because a T6 in pairing mode does not always
 * advertise either, and the official app finds it anyway.
 */
data class Nearby(val device: BluetoothDevice, val name: String, val rssi: Int, val likely: Boolean)

/** A finished task: its name, whether it worked, and the reason when it did not. */
data class TaskResult(val task: String, val ok: Boolean, val message: String = "")

data class UiState(
    val stage: Stage = Stage.IDLE,
    val phoneIp: String? = null,
    val deviceIp: String? = null,
    val identity: String = "",
    val secondsSinceHeartbeat: Long? = null,
    val busy: String? = null,
    /** What the running task is doing right now, in words for the person holding the phone. */
    val phase: String? = null,
    /** When the task and the current phase began, so the screen can show time passing. */
    val busySince: Long = 0,
    val phaseSince: Long = 0,
    /** How the last task ended, kept on screen until the next one starts. */
    val result: TaskResult? = null,
    val found: List<Nearby> = emptyList(),
    val scanning: Boolean = false,
    /** When the running scan stops, for a countdown on the button. */
    val scanUntil: Long = 0,
    val log: List<String> = emptyList(),
)

/**
 * The one session of this app, process-wide so it outlives the Activity. Holds no Wi-Fi
 * password and no device credential: what is on disk is the stage, the device address and
 * the md5 of the patched programs.
 */
@SuppressLint("MissingPermission")
class Engine private constructor(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val stateFile = File(context.filesDir, "session.json")
    private val keys = SshKeys(context.filesDir)
    private val state = MutableStateFlow(UiState())
    private val time = SimpleDateFormat("HH:mm:ss", Locale.US)

    private var api: DeviceApi? = null
    private var stopScan: (() -> Unit)? = null
    private var server: PhoneServer? = null
    private var installed: Installed? = null
    private var regionUrl = defaultRegion().url
    private var saved: Pair<String?, DeviceApi.Identity?>? = null
    private var restoredType = ""
    private var restoredFirmware = ""

    val ui: StateFlow<UiState> = state.asStateFlow()
    val privateKey get() = keys.privateKeyPem

    init {
        restore()
        scope.launch {
            while (true) {
                val a = api
                val known = a?.deviceIp to a?.identity
                if (a?.deviceIp != null && known != saved) {
                    saved = known
                    persist(state.value.stage)
                }
                state.update {
                    it.copy(
                        deviceIp = a?.deviceIp ?: it.deviceIp,
                        identity = a?.identity?.let { i -> "${i.type} / ${i.firmware}" } ?: it.identity,
                        secondsSinceHeartbeat = a?.lastHeartbeatMs?.takeIf { ms -> ms > 0 }
                            ?.let { ms -> (System.currentTimeMillis() - ms) / 1000 },
                    )
                }
                delay(1000)
            }
        }
    }

    val region get() = regionUrl

    fun setRegion(url: String) { regionUrl = url }

    /**
     * Every nearby BLE device, PetKit-looking ones first, then by signal. Nothing is
     * filtered out by name: a box whose advertisement carries neither a `Petkit` name nor
     * the 0xAAA0 service would otherwise be invisible here while the official app, which
     * does not filter that way, finds it. A wrong pick is harmless — [BleProvisioner]
     * writes nothing to a device without the 0xAAA0 service. Says what it saw when it
     * ends: an empty list alone reads as a button that did nothing.
     */
    fun scan() {
        if (state.value.scanning) return
        val scanner = context.getSystemService(BluetoothManager::class.java).adapter.bluetoothLeScanner
        if (scanner == null) return log("蓝牙没打开")
        state.update {
            it.copy(found = emptyList(), scanning = true, scanUntil = System.currentTimeMillis() + SCAN_MS)
        }
        val callback = object : ScanCallback() {
            override fun onScanResult(type: Int, result: ScanResult) {
                val name = result.scanRecord?.deviceName ?: result.device.name ?: ""
                if (ACCESSORIES.any { name.startsWith(it, ignoreCase = true) }) return
                val provisioning = result.scanRecord?.serviceUuids?.any { it == PROVISIONING } == true
                val likely = provisioning || name.contains("petkit", ignoreCase = true)
                state.update {
                    val known = it.found.firstOrNull { d -> d.device.address == result.device.address }
                    // The name and the service UUID often come in different packets (advertisement
                    // and scan response), so both are merged rather than overwritten. A row only
                    // changes when it learns something, so the list does not jump under a finger.
                    val merged = Nearby(
                        result.device,
                        name.ifEmpty { known?.name.orEmpty() },
                        known?.rssi ?: result.rssi,
                        likely || known?.likely == true,
                    )
                    if (known != null && known.name == merged.name && known.likely == merged.likely) it
                    else it.copy(
                        found = (it.found.filterNot { d -> d.device.address == result.device.address } + merged)
                            .sortedWith(compareByDescending<Nearby> { d -> d.likely }.thenByDescending { d -> d.rssi }),
                    )
                }
            }

            override fun onScanFailed(errorCode: Int) = log("蓝牙扫描失败（$errorCode）")
        }
        log("扫描 ${SCAN_MS / 1000} 秒…")
        scanner.startScan(callback)
        val timer = scope.launch { delay(SCAN_MS) }
        stopScan = { timer.cancel() }
        scope.launch {
            timer.join()
            stopScan = null
            runCatching { scanner.stopScan(callback) }
            val found = state.value.found
            val likely = found.filter { it.likely }
            val named = found.filter { it.name.isNotEmpty() }.map { it.name }.distinct()
            log(
                "扫描结束：看到 ${found.size} 个蓝牙设备，像 PetKit 的 ${likely.size} 台" +
                    if (named.isNotEmpty()) "。有名字的：${named.take(20).joinToString()}" else "",
            )
            if (likely.isEmpty() && found.isNotEmpty()) {
                log("没有认出 PetKit：在“其他设备”里按信号最强、名字最像的试，选错不会写入任何东西")
            }
            if (found.isEmpty()) log("什么都没扫到：确认手机蓝牙和定位都已打开")
            state.update { it.copy(scanning = false) }
        }
    }

    /**
     * The network the phone is on, which is the one the device must join. Android gives an
     * app the name with location on and permitted, and never the saved password.
     */
    @Suppress("DEPRECATION")
    fun currentSsid(): String? =
        context.getSystemService(android.net.wifi.WifiManager::class.java).connectionInfo?.ssid
            ?.removeSurrounding("\"")?.takeIf { it.isNotEmpty() && it != "<unknown ssid>" }

    /**
     * Nearby network names, strongest first. Not filtered by band: a T6 joins 5 GHz as
     * well as 2.4 GHz (seen on hardware), so a 5 GHz-only network is a valid choice.
     */
    fun nearbyNetworks(): List<String> =
        runCatching {
            context.getSystemService(android.net.wifi.WifiManager::class.java).scanResults
                .filter { it.SSID.isNotEmpty() }
                .sortedByDescending { it.level }
                .map { it.SSID }
                .distinct()
                .take(12)
        }.getOrDefault(emptyList())

    /**
     * BLE-point the device at this phone. The Wi-Fi password lives only in this call.
     *
     * A scan still running is stopped first: on Android an LE scan and a GATT connection
     * share the radio, and a device row can be tapped while the scan that listed it goes on.
     * The stage moves on only once the device has taken the configuration — a failure here
     * leaves it IDLE, so the Wi-Fi form stays on screen for the next try.
     */
    fun provision(device: BluetoothDevice, ssid: String, password: String) = task("配网") {
        step("准备连接设备")
        stopScan?.let {
            log("先停止扫描再连接")
            it()
            delay(SCAN_SETTLE_MS)
        }
        val ip = wifiAddress() ?: error("手机没连 Wi-Fi，或拿不到 IPv4 地址")
        startServer(ip)
        val url = api!!.phoneUrl
        log("服务地址 $url")
        SessionService.start(context)
        try {
            BleProvisioner(context, ::log, ::step).provision(device, BleProvisioner.Wifi(ssid, password), url)
        } catch (e: Exception) {
            SessionService.stop(context)
            throw e
        }
        persist(Stage.DEVICE_ON_PHONE)
    }

    fun probe() = task("只读探针") { log(orchestrator().probe()) }

    fun install() = task("打补丁") {
        val md5 = orchestrator().install()
        installed = md5
        persist(Stage.PATCHED)
        log("等设备回到手机后点复查")
    }

    fun verify() = task("复查") {
        orchestrator().verify(installed ?: error("这个会话没有打过补丁"))
    }

    fun handBack() = task("交还官方") {
        orchestrator().handBack(regionUrl, installed)
        persist(Stage.HANDED_BACK)
        log("交还完成。到官方 App 确认设备在线，并关闭自动升级")
        SessionService.stop(context)
    }

    fun remove(alsoSsh: Boolean) = task("移除补丁") {
        orchestrator().remove(alsoSsh)
        installed = null
        persist(if (alsoSsh) Stage.IDLE else state.value.stage)
    }

    /**
     * Whether [ip] answers at all, and whether its SSH port does. `ping` is the system's
     * own binary, which an app may run without root; port 22 is tried directly, because a
     * device can answer ping with no sshd behind it.
     */
    fun checkReachable(ip: String) = task("检测连通") {
        val target = ip.trim().ifEmpty { error("先填设备 IP") }
        val reachable = withContext(Dispatchers.IO) {
            runCatching {
                val process = ProcessBuilder("ping", "-c", "3", "-W", "2", target).redirectErrorStream(true).start()
                val output = process.inputStream.bufferedReader().readText()
                process.waitFor()
                output.lines().firstOrNull { "packet" in it }?.let { log("ping：${it.trim()}") }
                process.exitValue() == 0
            }.getOrElse {
                log("ping 运行不了（${it.message}），只看 22 端口")
                false
            }
        }
        val port = withContext(Dispatchers.IO) {
            try {
                java.net.Socket().use { it.connect(java.net.InetSocketAddress(target, 22), 3_000) }
                Rescue.Port.OPEN
            } catch (e: java.net.ConnectException) {
                Rescue.Port.REFUSED
            } catch (e: Exception) {
                Rescue.Port.TIMEOUT
            }
        }
        log(Rescue.describe(reachable, port))
    }

    /** Log in with this phone's key and show the boot script, changing nothing. */
    fun rescueRead(ip: String) = task("读取启动脚本") {
        val output = rescueSsh(ip).exec(Rescue.READ)
        val script = Rescue.script(output)
        log(output.substringBefore(Rescue.PRESENT).trim().ifEmpty { "（没有 ${BootScript.PATH}*）" })
        if (script != null) log("启动脚本 ${script.length} 字节：${BootScript.visible(script)}")
        log(Rescue.describe(Rescue.verdict(script)))
    }

    /** Move the boot script aside and reboot into the stock init. */
    fun rescueMoveAside(ip: String) = task("改名启动脚本并重启") {
        val script = Rescue.script(rescueSsh(ip).exec(Rescue.READ))
        val verdict = Rescue.verdict(script)
        if (verdict == Rescue.Verdict.MISSING || verdict == Rescue.Verdict.HEALTHY) {
            error(Rescue.describe(verdict))
        }
        val out = rescueSsh(ip).exec(Rescue.MOVE_ASIDE)
        if ("MOVED" !in out) error("改名没有成功：$out")
        log("已改名并重启。约 1 分钟后屏幕应亮起；再按原来的流程配网")
    }

    private fun rescueSsh(ip: String): DeviceSsh {
        val target = ip.trim().ifEmpty { error("先填设备 IP") }
        return DeviceSsh(keys) { target }
    }

    /** Drop the session record. The device is not touched. */
    fun reset() {
        stateFile.delete()
        installed = null
        server?.stop()
        server = null
        api = null
        SessionService.stop(context)
        state.update { UiState(log = it.log) }
    }

    private fun orchestrator(): Orchestrator {
        val ssh = DeviceSsh(keys) { api?.deviceIp ?: state.value.deviceIp }
        val link = server ?: startServer(wifiAddress() ?: error("手机没连 Wi-Fi"))
        return Orchestrator(
            link = link,
            ssh = ssh,
            dropbear = { context.assets.open("dropbear-mipsel").use { it.readBytes() } },
            publicKey = keys.publicKey,
            log = ::log,
            phase = ::step,
        )
    }

    private fun startServer(ip: String): PhoneServer {
        server?.let { return it }
        val deviceApi = DeviceApi(ip, PORT)
        state.value.deviceIp?.let { deviceApi.restoreDevice(it, restoredType, restoredFirmware) }
        val http = PhoneServer(deviceApi, PORT, ::log)
        http.start(30_000, false)
        log("手机服务已在 $ip:$PORT 监听")
        api = deviceApi
        server = http
        state.update { it.copy(phoneIp = ip) }
        return http
    }

    private fun task(name: String, block: suspend () -> Unit) {
        if (state.value.busy != null) return log("上一步还在跑")
        val now = System.currentTimeMillis()
        state.update { it.copy(busy = name, phase = null, busySince = now, phaseSince = now, result = null) }
        scope.launch {
            log("▶ $name")
            try {
                block()
                log("✓ $name")
                state.update { it.copy(result = TaskResult(name, true)) }
            } catch (e: Exception) {
                log("✗ $name 失败：${e.message}")
                state.update { it.copy(result = TaskResult(name, false, e.message.orEmpty())) }
            } finally {
                state.update { it.copy(busy = null, phase = null) }
            }
        }
    }

    private fun persist(stage: Stage) {
        state.update { it.copy(stage = stage) }
        val record = JSONObject()
            .put("stage", stage.name)
            .put("deviceIp", api?.deviceIp ?: state.value.deviceIp)
            .put("type", api?.identity?.type ?: restoredType)
            .put("firmware", api?.identity?.firmware ?: restoredFirmware)
            .put("region", regionUrl)
        installed?.let { record.put("bleMd5", it.bleMd5).put("ctrlMd5", it.ctrlMd5) }
        stateFile.writeText(record.toString())
    }

    private fun restore() {
        if (!stateFile.exists()) return
        val record = runCatching { JSONObject(stateFile.readText()) }.getOrNull() ?: return
        regionUrl = record.optString("region", regionUrl)
        restoredType = record.optString("type")
        restoredFirmware = record.optString("firmware")
        if (record.has("bleMd5")) installed = Installed(record.getString("bleMd5"), record.getString("ctrlMd5"))
        val stage = Stage.valueOf(record.optString("stage", Stage.IDLE.name))
        state.update { it.copy(stage = stage, deviceIp = record.optString("deviceIp").ifEmpty { null }) }
        if (stage == Stage.DEVICE_ON_PHONE || stage == Stage.PATCHED) {
            wifiAddress()?.let(::startServer)
            SessionService.start(context)
            log("续上之前的会话：设备还指着这台手机")
        }
    }

    private fun wifiAddress(): String? {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        // A VPN running over Wi-Fi reports TRANSPORT_WIFI as well, and its tunnel address is
        // one the device can never reach: sent as the server address, it fails provisioning
        // with "连不上服务器" after the device has already joined the Wi-Fi.
        val network = manager.allNetworks.firstOrNull {
            val caps = manager.getNetworkCapabilities(it)
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true &&
                !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        } ?: return null
        return manager.getLinkProperties(network)?.linkAddresses
            ?.map { it.address }?.filterIsInstance<Inet4Address>()?.firstOrNull()?.hostAddress
    }

    /** Name the phase the running task has reached. Repeating the current one changes nothing. */
    private fun step(text: String) {
        state.update {
            if (it.busy == null || it.phase == text) it
            else it.copy(phase = text, phaseSince = System.currentTimeMillis())
        }
    }

    private fun log(line: String) {
        val stamped = "${time.format(Date())} $line"
        state.update { it.copy(log = (it.log + stamped).takeLast(LOG_LINES)) }
    }

    class Region(val label: String, val url: String)

    companion object {
        private const val PORT = 8080
        private const val SCAN_MS = 15_000L
        private const val SCAN_SETTLE_MS = 600L
        private const val LOG_LINES = 1000

        /** Same servers as `http/proxy.py`'s `UPSTREAM_PRESETS`. */
        val REGIONS = listOf(
            Region("亚洲", "https://api.petktasia.com/6/"),
            Region("中国", "https://api.petkit.cn/6/"),
            Region("欧洲", "https://api-eu.petkt.com/6/"),
            Region("美洲", "https://api.petkt.com/6/"),
            Region("俄罗斯", "https://api-ru.petkit.cn/6/"),
        )

        private val PROVISIONING = android.os.ParcelUuid.fromString("0000aaa0-0000-1000-8000-00805f9b34fb")

        private val ACCESSORIES = listOf(
            "Petkit_W5", "Petkit_W4X", "Petkit_CTW2", "Petkit_CTW3", "Petkit_K2", "Petkit_K3",
        )

        /** A starting guess from the phone's time zone. The user confirms it on screen. */
        fun defaultRegion(): Region {
            val zone = TimeZone.getDefault().id
            return when {
                zone == "Asia/Shanghai" -> REGIONS[1]
                zone.startsWith("Asia/") || zone.startsWith("Australia/") -> REGIONS[0]
                zone.startsWith("Europe/") -> REGIONS[2]
                zone.startsWith("America/") -> REGIONS[3]
                else -> REGIONS[0]
            }
        }

        @Volatile private var instance: Engine? = null

        fun get(context: Context): Engine = instance ?: synchronized(this) {
            instance ?: Engine(context.applicationContext).also { instance = it }
        }
    }
}
