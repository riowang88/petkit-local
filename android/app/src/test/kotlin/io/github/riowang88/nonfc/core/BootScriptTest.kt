package io.github.riowang88.nonfc.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BootScriptTest {
    /** The wrapper `generate_app_init_wrapper` wrote on the development T6. */
    private val nasWrapper = """
        #!/bin/sh
        # /system/app_init.sh - petkit-local patcher wrapper
        # Auto-generated. Bind-mounts patched files before running the stock init.
        # Persistent SSH (dropbear on port 22)
        mkdir -p /tmp/.ssh
        cp /system/authorized_keys /tmp/.ssh/authorized_keys
        /system/dropbear -r /system/dbkey_ecdsa -p 22 &
        # Local storage: patched cloud accepts LAN IPs + skips TLS verify
        mount --bind /system/cloud_patched /app/bin/cloud
        mount --bind /app/bin/tserver /app/bin/agora

        # Run the stock init (which starts all processes using the patched files)
        . /app/script/app_init.sh
    """.trimIndent() + "\n"

    @Test
    fun mergeKeepsEverythingAndMountsBeforeStockInit() {
        val merged = BootScript.withNoNfc(nasWrapper)
        assertEquals(emptyList<String>(), BootScript.lost(nasWrapper, merged))
        assertTrue(BootScript.hasNoNfc(merged))
        val lines = merged.lines()
        val stock = lines.indexOf(". /app/script/app_init.sh")
        assertTrue(lines.indexOf("mount --bind /system/ble_patched /app/bin/ble") < stock)
        assertTrue(lines.indexOf("mount --bind /system/ctrl_nonfc /app/bin/ctrl") < stock)
    }

    @Test
    fun mergeIsIdempotent() {
        val once = BootScript.withNoNfc(nasWrapper)
        assertEquals(once, BootScript.withNoNfc(once))
    }

    @Test
    fun noWrapperGetsAFreshOne() {
        val fresh = BootScript.withNoNfc(null)
        assertEquals(BootScript.Kind.OURS, BootScript.kind(fresh))
        assertTrue(fresh.startsWith("#!/bin/sh\n"))
        assertFalse(BootScript.hasSsh(fresh))
    }

    @Test
    fun aScriptWeDidNotWriteIsNeverEdited() {
        val foreign = "#!/bin/sh\necho mine\n. /app/script/app_init.sh\n"
        assertEquals(BootScript.Kind.FOREIGN, BootScript.kind(foreign))
        assertThrows(NoNfcPatch.Refused::class.java) { BootScript.withNoNfc(foreign) }
    }

    @Test
    fun mqttIsDetected() {
        assertTrue(BootScript.hasMqtt(nasWrapper + "mount --bind /system/ctrl_patched /app/bin/ctrl\n"))
        assertFalse(BootScript.hasMqtt(nasWrapper))
    }

    @Test
    fun otherMountsAreReported() {
        assertEquals(
            listOf(
                "mount --bind /system/cloud_patched /app/bin/cloud",
                "mount --bind /app/bin/tserver /app/bin/agora",
            ),
            BootScript.otherMounts(BootScript.withNoNfc(nasWrapper)),
        )
    }

    @Test
    fun removalRestoresTheOriginal() {
        val merged = BootScript.withNoNfc(nasWrapper)
        assertEquals(nasWrapper.trimEnd(), BootScript.withoutNoNfc(merged).trimEnd())
    }

    @Test
    fun aWrapperWithOnlyOurBlocksIsBare() {
        val ours = BootScript.withNoNfc(BootScript.withSsh(null))
        assertFalse(BootScript.isBare(BootScript.withoutNoNfc(ours)))
        assertTrue(BootScript.isBare(BootScript.withoutSsh(BootScript.withoutNoNfc(ours))))
        assertFalse(BootScript.isBare(BootScript.withoutSsh(BootScript.withoutNoNfc(BootScript.withNoNfc(nasWrapper)))))
    }

    @Test
    fun `a refused script is shown with its invisible bytes`() {
        assertEquals("#!/bin/sh⏎\\x00\\x1b⏎", BootScript.visible("#!/bin/sh\n\u0000\u001b\n"))
    }
}
