package io.github.riowang88.nonfc.core

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceApiTest {
    private val device = "192.168.1.50"
    private val api = DeviceApi("192.168.1.2", 8080)

    private fun call(path: String, query: Map<String, String> = emptyMap(), from: String = device, now: Long = 1_000_000L) =
        api.handle(path, query, from, now)

    private fun json(reply: DeviceApi.Reply) = JSONObject(String(reply.body))

    @Test
    fun signupNeverCarriesASecret() {
        val reply = json(call("/6/t6/dev_signup", mapOf("firmware" to "951", "sn" to "x")))
        assertEquals(0, reply.getJSONObject("result").length())
        assertEquals("t6", api.identity!!.type)
        assertEquals("951", api.identity!!.firmware)
        assertEquals(device, api.deviceIp)
        assertEquals(1, api.signups)
    }

    @Test
    fun unknownPathsNever404() {
        val reply = call("/6/t6/dev_something_new")
        assertEquals(200, reply.status)
        assertEquals(0, json(reply).getJSONObject("result").length())
    }

    @Test
    fun serverInfoPointsAtThePhoneUntilHandBack() {
        val local = json(call("/6/t6/dev_serverinfo")).getJSONObject("result")
        assertEquals("http://192.168.1.2:8080/6/", local.getJSONArray("apiServers").getString(0))
        assertEquals(0, local.getJSONArray("ipServers").length())
        assertEquals(0L, api.handedBackAtMs)

        api.officialUrl = "https://api.petktasia.com/6/"
        api.mode = DeviceApi.Mode.HAND_BACK
        val official = json(call("/6/t6/dev_serverinfo")).getJSONObject("result")
        assertEquals("https://api.petktasia.com/6/", official.getJSONArray("apiServers").getString(0))
        assertEquals("https://api.petktasia.com/6/", official.getJSONArray("ipServers").getString(0))
        assertEquals(1_000_000L, api.handedBackAtMs)
    }

    @Test
    fun iotPointsAtABrokerThatIsNotThere() {
        val ali = json(call("/6/t6/dev_only_iot_device_info_v2", mapOf("id" to "347077")))
            .getJSONObject("result").getJSONObject("ali")
        assertEquals("192.168.1.2", ali.getString("mqttHost"))
        assertEquals(347077L, ali.getLong("id"))
    }

    @Test
    fun heartbeatDeliversRunCmdOnceAndSpacesTimestamps() {
        call("/6/t6/dev_signup")
        api.enqueue("one")
        api.enqueue("two")
        val entries = json(call("/6/poll/t6/heartbeat", now = 5_000_000L)).getJSONArray("result")
        assertEquals(2, entries.length())
        assertTrue(api.queueIsEmpty())
        val first = entries.getJSONObject(0)
        val second = entries.getJSONObject(1)
        assertEquals(
            "one",
            JSONObject(first.getString("content")).getJSONObject("user_cmd").getString("run_cmd"),
        )
        assertEquals(0, JSONObject(first.getString("content")).getInt("msgType"))
        assertEquals(first.getLong("timestamp") + 1, second.getLong("timestamp"))
        // time/1000 - timestamp must stay under 61, unsigned, so never negative.
        assertEquals(second.getLong("time") / 1000, second.getLong("timestamp"))

        val idle = json(call("/6/poll/t6/heartbeat", now = 5_001_000L)).getJSONArray("result")
        assertEquals(setOf("time"), idle.getJSONObject(0).keySet())
    }

    @Test
    fun aLaterCommandInTheSameSecondStillMovesForward() {
        call("/6/t6/dev_signup")
        api.enqueue("a")
        api.enqueue("b")
        call("/6/poll/t6/heartbeat", now = 5_000_000L)
        api.enqueue("c")
        val entry = json(call("/6/poll/t6/heartbeat", now = 5_000_000L)).getJSONArray("result").getJSONObject(0)
        assertEquals(5002L, entry.getLong("timestamp"))
    }

    @Test
    fun anotherHostGetsNoCommandsAndNoFiles() {
        call("/6/t6/dev_signup")
        api.enqueue("secret-command")
        val stranger = "192.168.1.99"
        val entries = json(call("/6/poll/t6/heartbeat", from = stranger)).getJSONArray("result")
        assertFalse(entries.getJSONObject(0).has("content"))
        assertFalse(api.queueIsEmpty())

        val url = api.stage("ble_patched", byteArrayOf(1, 2, 3))
        val path = url.substringAfter(":8080")
        assertEquals(3, call(path).body.size)
        assertEquals("application/json", call(path, from = stranger).contentType)
        api.unstage("ble_patched")
        assertEquals("application/json", call(path).contentType)
    }
}
