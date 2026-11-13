package io.github.riowang88.nonfc.device

import io.github.riowang88.nonfc.core.DeviceApi
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL

/** Real sockets, the way a device arrives: GET with a query, POST with a form body. */
class PhoneServerTest {
    private val port = 18080
    private val api = DeviceApi("127.0.0.1", port)
    private val server = PhoneServer(api, port).apply { start(5_000, false) }

    @After fun stop() = server.stop()

    private fun request(path: String, post: String? = null): Pair<Int, ByteArray> {
        val c = URL("http://127.0.0.1:$port$path").openConnection() as HttpURLConnection
        if (post != null) {
            c.requestMethod = "POST"
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            c.outputStream.write(post.toByteArray())
        }
        return c.responseCode to c.inputStream.readBytes()
    }

    @Test
    fun aBootSequenceReachesCommands() {
        val signup = request("/6/t6/dev_signup?mac=aa&sn=SN&id=347077&bt_mac=bb&hardware=1&firmware=951")
        assertEquals(0, JSONObject(String(signup.second)).getJSONObject("result").length())
        assertEquals("951", api.identity!!.firmware)

        val ota = request("/6/t6/dev_ota_check", post = "a=b&c=d")
        assertEquals(200, ota.first)

        api.enqueue("id")
        val beat = JSONObject(String(request("/6/poll/t6/heartbeat?iotStatus=0").second))
        assertTrue("run_cmd" in beat.getJSONArray("result").getJSONObject(0).getString("content"))
    }

    @Test
    fun aStagedFileCrossesIntact() {
        request("/6/t6/dev_signup?firmware=951")
        val data = ByteArray(2_000_000) { it.toByte() }
        val path = api.stage("ctrl_nonfc", data).substringAfter(":$port")
        assertArrayEquals(data, request(path).second)
    }

    /** Over a real socket, because the status was once dropped between the two classes. */
    @Test
    fun aMissingStagedFileIsA404OnTheWire() {
        request("/6/t6/dev_signup?firmware=951")
        val path = api.stage("app_init.sh", byteArrayOf(1)).substringAfter(":$port")
        api.unstage("app_init.sh")
        val c = URL("http://127.0.0.1:$port$path").openConnection() as HttpURLConnection
        assertEquals(404, c.responseCode)
    }

    @Test
    fun aResumedSessionKnowsItsDevice() {
        api.restoreDevice("127.0.0.1", "t6", "951")
        api.enqueue("id")
        val beat = JSONObject(String(request("/6/poll/t6/heartbeat").second))
        assertTrue(beat.getJSONArray("result").getJSONObject(0).has("content"))
        assertEquals("t6", server.identity!!.type)
    }
}
