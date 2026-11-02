package io.github.riowang88.nonfc.core

import java.security.MessageDigest

/**
 * The byte edits of `addon/petkit_local/patchers/no_nfc.py`, T6 firmware 1.951 only.
 *
 * Offsets are file offsets and every `before` must be present; the patch never searches.
 * Keep this file in step with the Python module: `NoNfcPatchTest` reproduces its target
 * SHA-256 from the real originals.
 */
object NoNfcPatch {
    const val BLE_ORIGINAL = "806a9bcdefb1dc1e77da3cae2d611b263df94697ca90d3ccb5ef3ec2f28f11a1"
    const val BLE_TARGET = "f24f40daa8d4fc511d1be7cc29da8938328f3caa24785e97f4c5b8a2ca651579"
    const val CTRL_ORIGINAL = "70c227e00ec0c806823c09065f1ad594336d04f9e85f40fb6bb9274db6edfdf5"
    const val CTRL_PREVIOUS = "df5b8f92aad1955bee2b5b0b55ed05721a399c84fd520d53f7369409b0d985dd"
    const val CTRL_ERR28 = "f81dbdd1e9ada240b5cdd4cf7f961701fc892a38d7e96939282196cccc0f88d8"
    const val CTRL_INSTALL = "6736a8eb25c58bc005076883c219503ba0e75025d9901075314dee05cd4a3048"
    const val CTRL_TARGET = "1acfac3cf478c46ec25b4a7fbce8b78ec7bf03f3c7607e1da9a969ec5fee1ffd"

    const val BLE_NAME = "ble_patched"
    const val CTRL_NAME = "ctrl_nonfc"

    private class Edit(val offset: Int, before: String, after: String) {
        val before = hex(before)
        val after = hex(after)
    }

    private val BLE_EDITS = listOf(
        Edit(281808, "05000224", "00000224"),
        Edit(
            281752,
            "04001c3c98349c2721e099035480828f0000428c0800e003782b428c",
            "4800033c7c39638c782b628ca42a638c010004240800e0030a108300",
        ),
    )
    private val CTRL_GETTER = listOf(Edit(761316, "05000224", "00000224"))
    private val CTRL_ERR28_EDITS = listOf(
        Edit(0xaebf4, "04004014", "04000010"),
        Edit(0xaecf8, "44014014", "17000010"),
    )
    private val CTRL_INSTALL_EDITS = listOf(Edit(0x3b3b4, "1c01068e", "01000624"))
    private val CTRL_PANEL_EDITS = listOf(
        Edit(0xa7ce8, "03004014", "03000010"),
        Edit(0xad2b0, "07006010", "01000010"),
        Edit(0xae9c0, "0c006010", "01000010"),
        Edit(0xaea50, "08006010", "01000010"),
    )

    class Refused(message: String) : Exception(message)

    /** Both programs ready to install, or `upToDate` when the device already runs them. */
    class Result(val ble: ByteArray, val ctrl: ByteArray, val upToDate: Boolean)

    /**
     * Patch a pair read off the device. Accepts a locked original, or an earlier
     * candidate of this series for `ctrl`. Anything else is refused: that is the only
     * thing standing between this tool and a build the edits were never checked against.
     */
    fun prepare(ble: ByteArray, ctrl: ByteArray): Result {
        val bleSha = sha256(ble)
        val ctrlSha = sha256(ctrl)
        if (bleSha == BLE_TARGET && ctrlSha == CTRL_TARGET) return Result(ble, ctrl, true)

        val outBle = when (bleSha) {
            BLE_TARGET -> ble
            BLE_ORIGINAL -> finish(apply(ble, "ble", BLE_EDITS), "ble", BLE_TARGET)
            else -> throw Refused("ble SHA-256 $bleSha 不是锁定的 1.951 原件")
        }
        val rest = when (ctrlSha) {
            CTRL_ORIGINAL -> CTRL_GETTER + CTRL_ERR28_EDITS + CTRL_INSTALL_EDITS + CTRL_PANEL_EDITS
            CTRL_PREVIOUS -> CTRL_ERR28_EDITS + CTRL_INSTALL_EDITS + CTRL_PANEL_EDITS
            CTRL_ERR28 -> CTRL_INSTALL_EDITS + CTRL_PANEL_EDITS
            CTRL_INSTALL -> CTRL_PANEL_EDITS
            else -> throw Refused("ctrl SHA-256 $ctrlSha 不是锁定的 1.951 原件")
        }
        if (bleSha == BLE_TARGET && ctrlSha == CTRL_ORIGINAL) {
            throw Refused("ble 已是目标版而 ctrl 是原件，状态不在已知序列里")
        }
        val outCtrl = finish(apply(ctrl, "ctrl", rest), "ctrl", CTRL_TARGET)
        return Result(outBle, outCtrl, false)
    }

    private fun apply(data: ByteArray, name: String, edits: List<Edit>): ByteArray {
        val out = data.copyOf()
        for (e in edits) {
            require(e.before.size == e.after.size) { "$name edit at ${e.offset} changes the size" }
            val end = e.offset + e.before.size
            if (end > out.size || !out.copyOfRange(e.offset, end).contentEquals(e.before)) {
                throw Refused("$name 偏移 ${e.offset} 的字节不是锁定值")
            }
            e.after.copyInto(out, e.offset)
        }
        return out
    }

    private fun finish(out: ByteArray, name: String, target: String): ByteArray {
        if (sha256(out) != target) throw Refused("$name 打完的 SHA-256 不是锁定目标")
        return out
    }

    fun sha256(data: ByteArray): String = digest("SHA-256", data)

    fun md5(data: ByteArray): String = digest("MD5", data)

    private fun digest(algorithm: String, data: ByteArray): String =
        MessageDigest.getInstance(algorithm).digest(data).joinToString("") { "%02x".format(it) }

    private fun hex(text: String): ByteArray =
        ByteArray(text.length / 2) { text.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}
