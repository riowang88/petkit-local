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
class PhoneServer(private val api: DeviceApi, port: Int) : NanoHTTPD(port), DeviceLink {
    override fun serve(session: IHTTPSession): Response {
        runCatching { session.parseBody(HashMap()) }
        val reply = api.handle(
            session.uri,
            parseQuery(session.queryParameterString),
            session.remoteIpAddress,
            System.currentTimeMillis(),
        )
        return newFixedLengthResponse(
            Response.Status.OK,
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
    override fun queueIsEmpty() = api.queueIsEmpty()
    override fun stage(name: String, data: ByteArray) = api.stage(name, data)
    override fun unstage(name: String) = api.unstage(name)

    override fun startHandBack(officialUrl: String) {
        api.officialUrl = officialUrl
        api.mode = DeviceApi.Mode.HAND_BACK
    }

    override suspend fun fetch(port: Int, name: String): ByteArray? = withContext(Dispatchers.IO) {
        val ip = api.deviceIp ?: return@withContext null
        runCatching {
            val connection = URL("http://$ip:$port/$name").openConnection() as HttpURLConnection
            connection.connectTimeout = 5_000
            connection.readTimeout = 30_000
            try {
                if (connection.responseCode == 200) connection.inputStream.readBytes() else null
            } finally {
                connection.disconnect()
            }
        }.getOrNull()
    }

    private fun parseQuery(raw: String?): Map<String, String> =
        raw.orEmpty().split('&').filter { '=' in it }.associate {
            val (key, value) = it.split('=', limit = 2)
            key to runCatching { URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)
        }
}
