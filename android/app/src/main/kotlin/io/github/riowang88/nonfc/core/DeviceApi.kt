package io.github.riowang88.nonfc.core

import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.max

/**
 * The device-facing side of the phone: the smallest subset of the local cloud a T6 needs
 * to boot, poll its heartbeat and take shell commands. Answers without forwarding anything.
 *
 * `dev_signup` answers an empty result ON PURPOSE. `parse_compare_signup_resinfo` saves any
 * `secret` that differs from the stored one, and PetKit answers 704 to a signature made
 * with one it does not know, so a local secret would cost the device its official identity.
 * With no `secret` key the parser returns 0 and changes nothing (emulated on the stock
 * 1.951 `ctrl`; see section 6 of the plan). That leaves the identity on the device, and
 * this class never sees it.
 *
 * Plain Kotlin with no Android or HTTP types, so the protocol is unit-tested on the JVM.
 */
class DeviceApi(private val phoneHost: String, private val port: Int) {
    enum class Mode { LOCAL, HAND_BACK }

    class Reply(
        val body: ByteArray,
        val contentType: String = "application/json",
        val status: Int = 200,
    )

    class Identity(val type: String, val firmware: String)

    @Volatile var mode = Mode.LOCAL

    /** Where `dev_serverinfo` sends the device in [Mode.HAND_BACK]. */
    @Volatile var officialUrl = ""

    /** The first address to sign up. Commands and staged files go to this one only. */
    @Volatile var deviceIp: String? = null
        private set

    @Volatile var identity: Identity? = null
        private set

    @Volatile var lastHeartbeatMs = 0L
        private set

    @Volatile var lastRequestMs = 0L
        private set

    /** Counts device boots: every one starts with a signup. */
    @Volatile var signups = 0
        private set

    /** When the official address was last served, 0 if never. */
    @Volatile var handedBackAtMs = 0L
        private set

    private val queue = ConcurrentLinkedQueue<String>()
    private val staged = ConcurrentHashMap<String, ByteArray>()
    private val token = ByteArray(8).also(SecureRandom()::nextBytes)
        .joinToString("") { "%02x".format(it) }
    private var lastTimestamp = 0L

    val phoneUrl get() = "http://$phoneHost:$port/6/"

    /**
     * What a previous run of the app recorded. A device signs up only when it boots, so a
     * resumed session would otherwise ignore its heartbeats until the next reboot.
     */
    fun restoreDevice(ip: String, type: String, firmware: String) {
        deviceIp = ip
        if (type.isNotEmpty()) identity = Identity(type, firmware)
    }

    fun enqueue(command: String) {
        queue.add(
            JSONObject()
                .put("msgType", 0)
                .put("user_cmd", JSONObject().put("run_cmd", command))
                .toString(),
        )
    }

    fun queueIsEmpty() = queue.isEmpty()

    fun stage(name: String, data: ByteArray): String {
        staged[name] = data
        return "http://$phoneHost:$port/patcher/$token/$name"
    }

    fun unstage(name: String) { staged.remove(name) }

    fun handle(path: String, query: Map<String, String>, remote: String, now: Long): Reply {
        lastRequestMs = now
        val parts = path.trim('/').split('/')
        if (parts.size == 3 && parts[0] == "patcher") return serveStaged(parts[1], parts[2], remote)
        return when (parts.last()) {
            "dev_syncTime" -> json("""{"result":${now / 1000}}""")
            "dev_signup" -> signup(parts, query, remote)
            "dev_serverinfo" -> serverInfo(now)
            "dev_iot_device_info", "dev_only_iot_device_info", "dev_only_iot_device_info_v2" ->
                iotInfo(query, now)
            "heartbeat" -> heartbeat(remote, now)
            "dev_ble_device" -> json("""{"result":{"list":[],"nextTick":3600}}""")
            else -> EMPTY
        }
    }

    private fun signup(parts: List<String>, query: Map<String, String>, remote: String): Reply {
        if (deviceIp == null) deviceIp = remote
        if (remote == deviceIp) {
            signups++
            // `/6/t6/dev_signup`; a bare `/6/dev_signup` names no type.
            val type = if (parts.size >= 3) parts[parts.size - 2] else ""
            identity = Identity(type, query["firmware"].orEmpty())
        }
        return EMPTY
    }

    /**
     * Shape proven on a T6 at the NAS (`payloads.to_serverinfo`): `ipServers` stays empty
     * and `dns` an empty string while the device is local. The official answer repeats
     * the address in `ipServers`, because PetKit's own reply always carries one there.
     */
    private fun serverInfo(now: Long): Reply {
        val handBack = mode == Mode.HAND_BACK
        if (handBack) handedBackAtMs = now
        val api = if (handBack) officialUrl else phoneUrl
        val body = JSONObject()
            .put("apiServers", JSONArray().put(api))
            .put("ipServers", if (handBack) JSONArray().put(api) else JSONArray())
            .put("dns", "")
            .put("pimServers", JSONObject.NULL)
            .put("linked", 1)
            .put("nextTick", 3600)
        return json(JSONObject().put("result", body).toString())
    }

    /**
     * Aliyun-wrapped credentials pointing at a broker that is not there: nothing on the
     * phone listens on 8883, so the device stays on the HTTP heartbeat, the state a T6
     * has held at the NAS for weeks. These values are overwritten by PetKit's real ones
     * the next time the device boots against it.
     */
    private fun iotInfo(query: Map<String, String>, now: Long): Reply {
        val ali = JSONObject()
            .put("id", query["id"]?.toLongOrNull() ?: 0L)
            .put("deviceName", query["sn"].orEmpty().ifEmpty { "local" })
            .put("deviceSecret", token + token)
            .put("iotPlatform", "ALI")
            .put("iotInstanceId", "local")
            .put("productKey", "local")
            .put("mqttHost", phoneHost)
            .put("createdAt", now)
            .put("type", 1)
            .put("regionId", "eu-central-1")
        return json(JSONObject().put("result", JSONObject().put("ali", ali)).toString())
    }

    /**
     * Rules from `http/handlers/heartbeat.py`: a `timestamp` not greater than the last one
     * consumed is dropped as seen, and one where `time/1000 - timestamp >= 61` as expired,
     * on an unsigned compare, so `time` has to move with it.
     *
     * Draining the queue happens inside this call and the HTTP layer sends the result with
     * no await in between, because a popped command cannot be put back.
     */
    private fun heartbeat(remote: String, now: Long): Reply {
        val entries = JSONArray()
        if (remote == deviceIp) {
            lastHeartbeatMs = now
            synchronized(queue) {
                while (true) {
                    val content = queue.poll() ?: break
                    val nowSeconds = now / 1000
                    val timestamp = max(nowSeconds, lastTimestamp + 1)
                    lastTimestamp = timestamp
                    entries.put(
                        JSONObject()
                            .put("time", now + (timestamp - nowSeconds) * 1000)
                            .put("timestamp", timestamp)
                            .put("content", content),
                    )
                }
            }
        }
        if (entries.length() == 0) entries.put(JSONObject().put("time", now))
        return json(JSONObject().put("result", entries).toString())
    }

    private fun serveStaged(wanted: String, name: String, remote: String): Reply {
        val data = staged[name]
        if (wanted != token || remote != deviceIp || data == null) return EMPTY
        return Reply(data, "application/octet-stream")
    }

    private fun json(text: String) = Reply(text.toByteArray())

    private companion object {
        /** Never a 404: firmware reads 4xx as a server fault and retries forever. */
        val EMPTY = Reply("""{"result":{}}""".toByteArray())
    }
}
