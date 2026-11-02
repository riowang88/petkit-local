package io.github.riowang88.nonfc.device

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import io.github.riowang88.nonfc.core.DeviceApi
import io.github.riowang88.nonfc.core.Installed
import io.github.riowang88.nonfc.core.Orchestrator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.net.Inet4Address
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Where a session stands. Written to disk on change, so a killed app can resume it. */
enum class Stage { IDLE, DEVICE_ON_PHONE, PATCHED, HANDED_BACK }

data class UiState(
    val stage: Stage = Stage.IDLE,
    val phoneIp: String? = null,
    val deviceIp: String? = null,
    val identity: String = "",
    val secondsSinceHeartbeat: Long? = null,
    val busy: String? = null,
    val found: List<BluetoothDevice> = emptyList(),
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

    fun scan() {
        val scanner = context.getSystemService(BluetoothManager::class.java).adapter.bluetoothLeScanner
        if (scanner == null) return log("蓝牙没打开")
        state.update { it.copy(found = emptyList()) }
        val callback = object : ScanCallback() {
            override fun onScanResult(type: Int, result: ScanResult) {
                val name = result.device.name ?: result.scanRecord?.deviceName ?: return
                if (!name.startsWith("Petkit") || ACCESSORIES.any(name::startsWith)) return
                state.update {
                    if (it.found.any { d -> d.address == result.device.address }) it
                    else it.copy(found = it.found + result.device)
                }
            }
        }
        scanner.startScan(callback)
        scope.launch {
            delay(SCAN_MS)
            scanner.stopScan(callback)
        }
    }

    /** BLE-point the device at this phone. The Wi-Fi password lives only in this call. */
    fun provision(device: BluetoothDevice, ssid: String, password: String) = task("配网") {
        val ip = wifiAddress() ?: error("手机没连 Wi-Fi，或拿不到 IPv4 地址")
        startServer(ip)
        val url = api!!.phoneUrl
        persist(Stage.DEVICE_ON_PHONE)
        SessionService.start(context)
        log("服务地址 $url")
        BleProvisioner(context, ::log).provision(device, BleProvisioner.Wifi(ssid, password), url)
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
        )
    }

    private fun startServer(ip: String): PhoneServer {
        server?.let { return it }
        val deviceApi = DeviceApi(ip, PORT)
        state.value.deviceIp?.let { deviceApi.restoreDevice(it, restoredType, restoredFirmware) }
        val http = PhoneServer(deviceApi, PORT)
        http.start(30_000, false)
        api = deviceApi
        server = http
        state.update { it.copy(phoneIp = ip) }
        return http
    }

    private fun task(name: String, block: suspend () -> Unit) {
        if (state.value.busy != null) return log("上一步还在跑")
        state.update { it.copy(busy = name) }
        scope.launch {
            log("▶ $name")
            try {
                block()
                log("✓ $name")
            } catch (e: Exception) {
                log("✗ $name 失败：${e.message}")
            } finally {
                state.update { it.copy(busy = null) }
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
        val network = manager.allNetworks.firstOrNull {
            manager.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        } ?: return null
        return manager.getLinkProperties(network)?.linkAddresses
            ?.map { it.address }?.filterIsInstance<Inet4Address>()?.firstOrNull()?.hostAddress
    }

    private fun log(line: String) {
        val stamped = "${time.format(Date())} $line"
        state.update { it.copy(log = (it.log + stamped).takeLast(LOG_LINES)) }
    }

    class Region(val label: String, val url: String)

    companion object {
        private const val PORT = 8080
        private const val SCAN_MS = 15_000L
        private const val LOG_LINES = 1000

        /** Same servers as `http/proxy.py`'s `UPSTREAM_PRESETS`. */
        val REGIONS = listOf(
            Region("亚洲", "https://api.petktasia.com/6/"),
            Region("中国", "https://api.petkit.cn/6/"),
            Region("欧洲", "https://api-eu.petkt.com/6/"),
            Region("美洲", "https://api.petkt.com/6/"),
            Region("俄罗斯", "https://api-ru.petkit.cn/6/"),
        )

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
