package io.github.riowang88.nonfc.core

/**
 * `/system/app_init.sh`, the boot wrapper every patcher shares.
 *
 * The add-on regenerates it from a recorded set of active patchers. This tool has no such
 * record, so it edits what is on the device, and refuses anything it did not recognise:
 * overwriting would delete the device's SSH, the one way back in that does not go through
 * the `ctrl` being replaced.
 */
object BootScript {
    const val STORE = "/system"
    const val PATH = "$STORE/app_init.sh"
    const val BACKUP = "$PATH.before_nonfc"

    private const val STOCK_LINE = ". /app/script/app_init.sh"
    private const val MARKER = "petkit-local patcher wrapper"

    private val NO_NFC_LINES = listOf(
        "# No-NFC bag: a missing tag is not treated as a missing bag",
        "mount --bind $STORE/${NoNfcPatch.BLE_NAME} /app/bin/ble",
        "mount --bind $STORE/${NoNfcPatch.CTRL_NAME} /app/bin/ctrl",
    )
    private val SSH_LINES = listOf(
        "# Persistent SSH (dropbear on port 22)",
        "mkdir -p /tmp/.ssh",
        "cp $STORE/authorized_keys /tmp/.ssh/authorized_keys",
        "$STORE/dropbear -r $STORE/dbkey_ecdsa -p 22 &",
    )

    enum class Kind { MISSING, OURS, FOREIGN }

    fun kind(text: String?): Kind = when {
        text == null -> Kind.MISSING
        MARKER in text && text.lines().count { it.trim() == STOCK_LINE } == 1 -> Kind.OURS
        else -> Kind.FOREIGN
    }

    fun hasSsh(text: String?): Boolean = text != null && "/dropbear " in text

    fun hasNoNfc(text: String?): Boolean =
        text != null && NO_NFC_LINES.drop(1).all { it in text.lines() }

    /** The MQTT patch replaces `ctrl` too; one mount would hide the other. */
    fun hasMqtt(text: String?): Boolean = text != null && "ctrl_patched" in text

    /** Bind mounts this tool did not put there, which outlive the hand-back. */
    fun otherMounts(text: String?): List<String> =
        (text ?: "").lines().map { it.trim() }
            .filter { it.startsWith("mount --bind") && it !in NO_NFC_LINES }

    fun fresh(): String =
        "#!/bin/sh\n# $PATH - $MARKER\n# Auto-generated. Bind-mounts patched files before " +
            "running the stock init.\n\n# Run the stock init (which starts all processes " +
            "using the patched files)\n$STOCK_LINE\n"

    fun withNoNfc(text: String?): String =
        if (hasNoNfc(text)) editable(text) else insert(editable(text), NO_NFC_LINES)

    fun withSsh(text: String?): String =
        if (hasSsh(text)) editable(text) else insert(editable(text), SSH_LINES)

    fun withoutNoNfc(text: String): String = drop(text, NO_NFC_LINES)

    fun withoutSsh(text: String): String = drop(text, SSH_LINES)

    /** Whether nothing but the stock init is left, so the wrapper itself can go. */
    fun isBare(text: String): Boolean =
        text.lines().map { it.trim() }
            .none { it.isNotEmpty() && !it.startsWith("#") && it != STOCK_LINE }

    /** Lines of `before` missing from `after`. A merge may only ever add. */
    fun lost(before: String, after: String): List<String> {
        val kept = after.lines().toSet()
        return before.lines().filter { it.isNotBlank() && it !in kept }
    }

    private fun editable(text: String?): String = when (kind(text)) {
        Kind.MISSING -> fresh()
        Kind.OURS -> text!!.trimEnd('\n') + "\n"
        Kind.FOREIGN -> throw NoNfcPatch.Refused("$PATH 不是 petkit-local 的包装脚本，不改")
    }

    private fun insert(text: String, block: List<String>): String {
        val lines = text.lines().toMutableList()
        lines.addAll(lines.indexOfFirst { it.trim() == STOCK_LINE }, block)
        return lines.joinToString("\n")
    }

    private fun drop(text: String, block: List<String>): String =
        text.lines().filter { it.trim() !in block }.joinToString("\n")
}
