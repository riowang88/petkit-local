package io.github.riowang88.nonfc.device

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.os.Build
import io.github.riowang88.nonfc.core.PkFrame
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Key 110 identity and key 151 provisioning over GATT 0xAAA0, transcribed from
 * `provisionPetkit` in `web/static/js/provision.js`, including its delays, which come from
 * captures of the official app. Only the Ingenic protocol: a T6 is an Ingenic device.
 */
@SuppressLint("MissingPermission")
class BleProvisioner(
    private val context: Context,
    private val log: (String) -> Unit,
    private val step: (String) -> Unit = {},
) {
    class Wifi(val ssid: String, val password: String)

    private val replies = ConcurrentHashMap<Int, JSONObject>()
    private var gatt: BluetoothGatt? = null
    private var write: BluetoothGattCharacteristic? = null
    private var mtu = 23
    private var seq = 0
    private var inbound = ByteArray(0)
    private var notifications = 0
    private var unparsed: ByteArray? = null

    @Volatile private var ready: CompletableDeferred<Unit>? = null
    @Volatile private var acked: CompletableDeferred<Boolean>? = null

    /** Provision the device, and report whether it said it reached the server. */
    suspend fun provision(device: BluetoothDevice, wifi: Wifi, server: String): Boolean {
        try {
            step("用蓝牙连接设备（几秒）")
            connect(device)
            log("已连上 GATT：MTU $mtu，写入${if (noAck()) "无" else "有"}响应")
            step("读取设备信息")
            var asked = 0
            while (!replies.containsKey(110)) {
                if (asked == IDENTITY_TRIES) error("设备没回应身份请求（${heard()}）。确认它刚进配网模式，且官方 App 已彻底关闭")
                if (asked > 0) log("没有身份回复（${heard()}），重试 key 110")
                send(JSONObject().put("key", 110))
                asked++
                awaitReply(110)
            }
            log("设备身份：${describe(replies[110])}")
            delay(1045)

            step("把 Wi-Fi 和手机地址发给设备")
            log("写入 Wi-Fi 和服务器地址（key 151）")
            send(JSONObject().put("key", 151).put("payload", payload(wifi, server)))
            for (i in 0 until 20) {
                if (replies[151]?.optInt("state") == 1) break
                delay(250)
            }
            if (replies[151]?.optInt("state") != 1) error("设备没有接受配置（无 state:1）")

            step("等设备连上 Wi-Fi（通常 1 分钟内，最长约 1 分半）")
            log("配置已接受，等设备入网")
            var shown: Int? = null
            for (i in 0 until 25) {
                delay(if (i == 0) 3340 else 3000)
                send(JSONObject().put("key", 112))
                val state = replies[112]?.takeIf { it.has("state") }?.getInt("state")
                if (state != shown) {
                    shown = state
                    log("设备状态：${PkFrame.JOIN_STATES[state] ?: "state $state"}")
                    // The device's own report is already plain words: show it as it comes.
                    PkFrame.JOIN_STATES[state]?.let { step("等设备连上 Wi-Fi：$it") }
                }
                if (state in PkFrame.JOIN_FAILED) error("设备放弃：${PkFrame.JOIN_STATES[state]}")
                if (state in PkFrame.JOIN_DONE) {
                    step("设备已连上 Wi-Fi，正在确认")
                    delay(5780)
                    send(JSONObject().put("key", 112))
                    delay(1030)
                    if (replies[112]?.optInt("state") in PkFrame.JOIN_DONE) {
                        log("设备已入网，发送完成（key 101）")
                        send(JSONObject().put("key", 101))
                        return true
                    }
                }
            }
            log("设备没有在时限内报告入网，它可能还会自己连上，看服务状态")
            return false
        } finally {
            gatt?.close()
            gatt = null
        }
    }

    /**
     * The app's own key-151 fields (`provision.js`): `hide` is the constant 1, the offset
     * goes out as a string of hours, and `locale` is a time zone name, not a language.
     * The password goes to the device and nowhere else; it is never logged or stored.
     */
    private fun payload(wifi: Wifi, server: String): JSONObject {
        val zone = TimeZone.getDefault()
        val hours = zone.getOffset(System.currentTimeMillis()) / 3_600_000.0
        return JSONObject()
            .put("ssid", wifi.ssid)
            .put("pwd", wifi.password)
            .put("hide", 1)
            .put("locale", zone.id)
            .put("timezone", String.format(Locale.US, "%.1f", hours))
            .put("apiServers", JSONArray().put(server))
            .put("ipServers", JSONArray().put(server))
    }

    private suspend fun connect(device: BluetoothDevice) {
        val connected = CompletableDeferred<Unit>()
        ready = connected
        gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
        withTimeout(TIMEOUT_MS) { connected.await() }
    }

    private suspend fun awaitReply(key: Int): Boolean {
        for (i in 0 until 20) {
            if (replies.containsKey(key)) return true
            delay(250)
        }
        return false
    }

    private suspend fun send(message: JSONObject) {
        val frame = PkFrame.frame(seq++, message.toString())
        val target = write ?: error("没有写特征")
        val noAck = noAck()
        // A with-response write longer than the MTU becomes an ATT long write, which is
        // what Web Bluetooth does. Without-response has no such thing, so it is cut up.
        val chunks = if (noAck) frame.toList().chunked(mtu - 3).map { it.toByteArray() } else listOf(frame)
        for (chunk in chunks) {
            val done = CompletableDeferred<Boolean>()
            acked = done
            target.writeType = if (noAck) {
                BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            } else {
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            }
            check(writeValue(target, chunk)) { "BLE 写入被拒绝" }
            check(withTimeout(TIMEOUT_MS) { done.await() }) { "BLE 写入失败" }
        }
    }

    private fun noAck(): Boolean =
        (write?.properties ?: 0) and BluetoothGattCharacteristic.PROPERTY_WRITE == 0

    /**
     * What came back so far, for a failure message: no notification at all points at the
     * subscription or a device not listening; notifications that do not parse point at the
     * framing, and their first bytes say how.
     */
    private fun heard(): String {
        val raw = unparsed ?: return "收到 $notifications 条通知"
        val hex = raw.take(24).joinToString(" ") { "%02x".format(it) }
        return "收到 $notifications 条通知，最后一条无法解析：$hex"
    }

    @Suppress("DEPRECATION")
    private fun writeValue(target: BluetoothGattCharacteristic, data: ByteArray): Boolean {
        val g = gatt ?: return false
        return if (Build.VERSION.SDK_INT >= 33) {
            g.writeCharacteristic(target, data, target.writeType) == BluetoothStatusCodes.SUCCESS
        } else {
            target.value = data
            g.writeCharacteristic(target)
        }
    }

    private fun onNotify(value: ByteArray) {
        notifications++
        // A reply can span notifications. Each frame opens with the magic and says its size.
        inbound = if (PkFrame.hasMagic(value)) value else inbound + value
        val size = PkFrame.expectedSize(inbound)
        if (size != null && inbound.size < size) return
        val message = PkFrame.parse(inbound)
        if (message == null || !message.has("key")) unparsed = inbound
        inbound = ByteArray(0)
        if (message == null || !message.has("key")) return
        val key = message.getInt("key")
        replies[key] = message.optJSONObject("payload") ?: JSONObject()
    }

    /** Key 110 carries no secret we want on screen: only the field names and short values. */
    private fun describe(payload: JSONObject?): String {
        if (payload == null) return "(空)"
        return payload.keys().asSequence()
            .filterNot { it.contains("secret", ignoreCase = true) }
            .joinToString { "$it=${payload.opt(it)}" }
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                g.requestMtu(512)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                ready?.completeExceptionally(IllegalStateException("BLE 断开（$status）"))
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, value: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) mtu = value
            g.discoverServices()
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val service = g.getService(SERVICE)
            val notify = service?.getCharacteristic(NOTIFY)
            write = service?.getCharacteristic(WRITE)
            val cccd = notify?.getDescriptor(CCCD)
            if (notify == null || write == null || cccd == null) {
                ready?.completeExceptionally(IllegalStateException("找不到 0xAAA0 配网服务"))
                return
            }
            g.setCharacteristicNotification(notify, true)
            enableNotify(g, cccd)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            // A refused subscription still "connects", then every reply is silently lost.
            if (status == BluetoothGatt.GATT_SUCCESS) {
                ready?.complete(Unit)
            } else {
                ready?.completeExceptionally(IllegalStateException("打开通知失败（status $status）"))
            }
        }

        override fun onCharacteristicWrite(
            g: BluetoothGatt,
            c: BluetoothGattCharacteristic,
            status: Int,
        ) {
            acked?.complete(status == BluetoothGatt.GATT_SUCCESS)
        }

        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            c: BluetoothGattCharacteristic,
            value: ByteArray,
        ) = onNotify(value)

        @Deprecated("Called below API 33")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < 33) onNotify(c.value)
        }
    }

    @Suppress("DEPRECATION")
    private fun enableNotify(g: BluetoothGatt, cccd: BluetoothGattDescriptor) {
        val on = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        if (Build.VERSION.SDK_INT >= 33) {
            g.writeDescriptor(cccd, on)
        } else {
            cccd.value = on
            g.writeDescriptor(cccd)
        }
    }

    private companion object {
        const val TIMEOUT_MS = 15_000L
        const val IDENTITY_TRIES = 3
        val SERVICE: UUID = UUID.fromString("0000aaa0-0000-1000-8000-00805f9b34fb")
        val NOTIFY: UUID = UUID.fromString("0000aaa1-0000-1000-8000-00805f9b34fb")
        val WRITE: UUID = UUID.fromString("0000aaa2-0000-1000-8000-00805f9b34fb")
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}
