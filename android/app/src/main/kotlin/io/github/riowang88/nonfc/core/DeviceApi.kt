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
        queue.add(wrap(command))
    }

    /**
     * Take back a command no heartbeat has collected yet. False means the device already
     * has it, and it may still run: whoever gave up waiting must assume that it will.
     */
    fun cancel(command: String): Boolean = synchronized(queue) { queue.remove(wrap(command)) }

    private fun wrap(command: String) = JSONObject()
        .put("msgType", 0)
        .put("user_cmd", JSONObject().put("run_cmd", command))
        .toString()

    fun queueIsEmpty() = queue.isEmpty()

    fun stage(name: String, data: ByteArray): String {
        staged[name] = data
        return "http://$phoneHost:$port/patcher/$token/$name"
    }

    fun unstage(name: String) { staged.remove(name) }

    fun handle(path: String, query: Map<String, String>, remote: String, now: Long): Reply {
        lastRequestMs = now
        val parts = path.trim('/').split('/')
        if (parts[0] == "patcher") return serveStaged(parts, remote)
        return when (parts.last()) {
            "dev_syncTime" -> json("""{"result":${now / 1000}}""")
            "dev_signup" -> signup(parts, query, remote)
            "dev_serverinfo" -> serverInfo(now)
            "dev_iot_device_info", "dev_only_iot_device_info", "dev_only_iot_device_info_v2" ->
                iotInfo(query, now)
            "heartbeat" -> heartbeat(remote, now)
            "dev_ble_device" -> json("""{"result":{"list":[],"nextTick":3600}}""")
            // Stock ctrl's `pk_send_http_event_msg` (network_event_state.c) does
            // `strcmp(cJSON_GetObjectItem(resp,"result")->valuestring, "success")` with no
            // null-check. An empty `{"result":{}}` makes `result` an OBJECT, valuestring is
            // NULL, and ctrl SIGSEGVs (invalid read from 0) every time it reports an event,
            // crashing and respawning every ~80s. The real cloud — and the add-on's
            // `handle_event_report` — answer the STRING "success"; mirror that. Confirmed on a
            // live T6: epc in libc str*, ra=0x439570 in ctrl, faulting address 0x00000000.
            "dev_event_report", "dev_ota_start", "dev_ota_complete" -> SUCCESS
            // ctrl's `union_info_parse_oci` reads `result.type` (a string) and iterates
            // `result.capability` (an array). An empty `{"result":{}}` leaves `type` absent,
            // and ctrl jumps through the missing field — a wild `jr` to ASCII (seen live:
            // epc==ra==badvaddr==0x61656270). The add-on's no-bucket answer is this exact
            // shape and runs in the field without crashing: type "oci", no upload targets.
            "dev_oss_sts_info_new", "dev_oss_sts_info_new_v2" ->
                json("""{"result":{"type":"oci","capability":[]}}""")
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

    /**
     * A miss is a 404, the one place this class answers one. This path is fetched by
     * `wget`, not by the firmware, and `wget` writes whatever a 200 carries into the file
     * it was told to: the 13 bytes of [EMPTY] served here once replaced a T6's
     * `/system/app_init.sh`, and the box booted with no ble, ctrl or screen. A miss is
     * routine, too — the command that fetches a file can reach the device after the
     * phone gave up on it and unstaged the file.
     */
    private fun serveStaged(parts: List<String>, remote: String): Reply {
        val data = if (parts.size == 3) staged[parts[2]] else null
        if (data == null || parts[1] != token || remote != deviceIp) return NOT_FOUND
        return Reply(data, "application/octet-stream")
    }

    private fun json(text: String) = Reply(text.toByteArray())

    private companion object {
        /**
         * Never a 404 to the FIRMWARE, which reads 4xx as a server fault and retries
         * forever. Staged files are the exception, see [serveStaged].
         */
        val EMPTY = Reply("""{"result":{}}""".toByteArray())

        /**
         * The report/ACK shape stock ctrl string-compares against. `result` MUST be the
         * string "success", not an object: ctrl reads `result.valuestring` unconditionally.
         */
        val SUCCESS = Reply("""{"result":"success"}""".toByteArray())
        val NOT_FOUND = Reply("not found\n".toByteArray(), "text/plain", 404)
    }
}
