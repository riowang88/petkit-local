package io.github.riowang88.nonfc.device

import fi.iki.elonen.NanoHTTPD
import io.github.riowang88.nonfc.core.DeviceApi
import io.github.riowang88.nonfc.core.DeviceLink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder

/**
 * Plain HTTP on 8080: Ingenic devices take the port from the URL they were provisioned
 * with, so no privileged port is needed. Request bodies are read and discarded; nothing
 * the device uploads matters here.
 */
class PhoneServer(
    private val api: DeviceApi,
    port: Int,
    private val log: (String) -> Unit = {},
) : NanoHTTPD(port), DeviceLink {
    private var idleBeats = 0
    private var lastFetchNote: String? = null

    /**
     * Every request is logged by method, path and peer, so a device that "cannot reach the
     * server" can be told apart from one that reached it and disliked the answer. The query
     * is left out: it carries the serial and MAC. Idle heartbeats are thinned to one in ten.
     */
    override fun serve(session: IHTTPSession): Response {
        runCatching { session.parseBody(HashMap()) }
        val reply = api.handle(
            session.uri,
            parseQuery(session.queryParameterString),
            session.remoteIpAddress,
            System.currentTimeMillis(),
        )
        val idle = session.uri.endsWith("/heartbeat") && "run_cmd" !in String(reply.body)
        if (!idle || idleBeats++ % 10 == 0) {
            log("← ${session.remoteIpAddress} ${session.method} ${session.uri} → ${reply.status} ${reply.body.size}B")
        }
        // The status has to cross: a staged-file miss sent as 200 is a file to `wget`.
        return newFixedLengthResponse(
            Response.Status.lookup(reply.status) ?: Response.Status.INTERNAL_ERROR,
            reply.contentType,
            ByteArrayInputStream(reply.body),
            reply.body.size.toLong(),
        )
    }

    override val identity get() = api.identity
    override val signups get() = api.signups
    override val lastRequestMs get() = api.lastRequestMs
    override val handedBackAtMs get() = api.handedBackAtMs

    override fun enqueue(command: String) = api.enqueue(command)
    override fun cancel(command: String) = api.cancel(command)
    override fun queueIsEmpty() = api.queueIsEmpty()
    override fun stage(name: String, data: ByteArray) = api.stage(name, data)
    override fun unstage(name: String) = api.unstage(name)

    override fun startHandBack(officialUrl: String) {
        api.officialUrl = officialUrl
        api.mode = DeviceApi.Mode.HAND_BACK
    }

    /**
     * The reverse direction: the phone reaching INTO the device's temporary httpd. A device
     * that heartbeats fine can still be unreachable this way (AP isolation blocks it, or the
     * httpd never came up), and that is exactly the failure to tell apart, so each attempt's
     * outcome is logged — deduplicated, because `capture` polls this every few seconds.
     */
    override suspend fun fetch(port: Int, name: String): ByteArray? = withContext(Dispatchers.IO) {
        val ip = api.deviceIp ?: return@withContext null.also { note("→ 回读 $port：还不知道设备 IP") }
        try {
            val connection = URL("http://$ip:$port/$name").openConnection() as HttpURLConnection
            connection.connectTimeout = 5_000
            connection.readTimeout = 30_000
            try {
                val code = connection.responseCode
                if (code == 200) {
                    val data = connection.inputStream.readBytes()
                    note("→ 回读 $ip:$port/$name → 200 ${data.size}B")
                    data
                } else {
                    note("→ 回读 $ip:$port/$name → HTTP $code")
                    null
                }
            } finally {
                connection.disconnect()
            }
        } catch (e: Exception) {
            note("→ 回读 $ip:$port 失败：${e.javaClass.simpleName} ${e.message ?: ""}".trim())
            null
        }
    }

    /** Log a reverse-fetch outcome, but not the same one twice in a row — polling repeats it. */
    private fun note(line: String) {
        if (line != lastFetchNote) {
            lastFetchNote = line
            log(line)
        }
    }

    private fun parseQuery(raw: String?): Map<String, String> =
        raw.orEmpty().split('&').filter { '=' in it }.associate {
            val (key, value) = it.split('=', limit = 2)
            key to runCatching { URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)
        }
}
