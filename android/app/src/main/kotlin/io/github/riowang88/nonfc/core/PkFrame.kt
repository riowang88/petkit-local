package io.github.riowang88.nonfc.core

import org.json.JSONObject

/**
 * PetKit Ingenic provisioning frames on GATT 0xAAA0: `FA FC FD 46 | type | seq | len16 |
 * json | crc16 | FB`, little-endian, where `len` counts the JSON plus the two CRC bytes.
 * Mirrors `web/static/js/provision.js`.
 */
object PkFrame {
    private val MAGIC = byteArrayOf(0xFA.toByte(), 0xFC.toByte(), 0xFD.toByte(), 0x46)
    private const val TAIL = 0xFB.toByte()
    private const val TYPE_OUT: Byte = 0x18
    private const val HEADER = 8

    /** CRC-16/CCITT-FALSE: poly 0x1021, init 0xFFFF, no reflection, xorout 0. */
    fun crc16(bytes: ByteArray): Int {
        var crc = 0xFFFF
        for (b in bytes) {
            crc = crc xor ((b.toInt() and 0xFF) shl 8)
            repeat(8) {
                crc = if (crc and 0x8000 != 0) (crc shl 1) xor 0x1021 else crc shl 1
                crc = crc and 0xFFFF
            }
        }
        return crc
    }

    fun frame(seq: Int, json: String): ByteArray {
        val body = json.toByteArray()
        val crc = crc16(body)
        val len = body.size + 2
        return MAGIC + byteArrayOf(
            TYPE_OUT, seq.toByte(), len.toByte(), (len shr 8).toByte(),
        ) + body + byteArrayOf(crc.toByte(), (crc shr 8).toByte(), TAIL)
    }

    /** Bytes one framed reply occupies, or null if `buf` does not yet say. */
    fun expectedSize(buf: ByteArray): Int? {
        if (!hasMagic(buf) || buf.size < HEADER) return null
        val len = (buf[6].toInt() and 0xFF) or ((buf[7].toInt() and 0xFF) shl 8)
        return HEADER + len + 1
    }

    fun hasMagic(buf: ByteArray): Boolean =
        buf.size >= MAGIC.size && MAGIC.indices.all { buf[it] == MAGIC[it] }

    /** One inbound reply: the framed envelope, or bare JSON. Null when it is neither. */
    fun parse(buf: ByteArray): JSONObject? {
        val text = if (hasMagic(buf) && buf.size >= HEADER + 3) {
            val size = expectedSize(buf) ?: return null
            val end = minOf(size, buf.size) - 3
            String(buf, HEADER, end - HEADER)
        } else {
            String(buf)
        }
        return runCatching { JSONObject(text) }.getOrNull()
    }

    /** Key 112's join report, from the app's own table (`provision.js`, PK_JOIN_STATES). */
    val JOIN_STATES = mapOf(
        0 to "启动中",
        1 to "正在找 Wi-Fi",
        2 to "正在连 Wi-Fi",
        3 to "Wi-Fi 密码错误",
        4 to "找不到这个 Wi-Fi",
        5 to "连不上 Wi-Fi",
        6 to "Wi-Fi 已连接，正在连服务器",
        7 to "已连上服务器",
        8 to "连不上服务器",
        9 to "正在连 MQTT",
        10 to "在线",
    )
    val JOIN_FAILED = setOf(4, 5, 8)
    val JOIN_DONE = setOf(7, 10)
}
