package io.github.riowang88.nonfc.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PkFrameTest {
    @Test
    fun crcMatchesTheCcittFalseCheckValue() {
        assertEquals(0x29B1, PkFrame.crc16("123456789".toByteArray()))
    }

    @Test
    fun frameMatchesProvisionJs() {
        // pkFrame(0, {key: 110}) from web/static/js/provision.js.
        val crc = PkFrame.crc16("""{"key":110}""".toByteArray())
        val expected = byteArrayOf(
            0xFA.toByte(), 0xFC.toByte(), 0xFD.toByte(), 0x46, 0x18, 0, 13, 0,
        ) + """{"key":110}""".toByteArray() + byteArrayOf(crc.toByte(), (crc shr 8).toByte(), 0xFB.toByte())
        assertArrayEquals(expected, PkFrame.frame(0, """{"key":110}"""))
    }

    @Test
    fun parseReadsItsOwnFramesAndBareJson() {
        val framed = PkFrame.frame(3, """{"key":112,"payload":{"state":7}}""")
        assertEquals(framed.size, PkFrame.expectedSize(framed))
        assertEquals(7, PkFrame.parse(framed)!!.getJSONObject("payload").getInt("state"))
        assertEquals(110, PkFrame.parse("""{"key":110}""".toByteArray())!!.getInt("key"))
        assertNull(PkFrame.parse(byteArrayOf(1, 2, 3)))
    }
}
