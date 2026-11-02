package io.github.riowang88.nonfc.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * The firmware is not committed. Point NONFC_STOCK_DIR at a directory holding the 1.951
 * `ble` and `ctrl` to prove these edits land on the same SHA-256 as `no_nfc.py`.
 */
class NoNfcPatchTest {
    private val dir = System.getenv("NONFC_STOCK_DIR")?.let(::File)

    private fun stock(name: String): ByteArray {
        assumeTrue("NONFC_STOCK_DIR not set", dir != null)
        return File(dir, name).readBytes()
    }

    @Test
    fun originalsReachTheLockedTargets() {
        val out = NoNfcPatch.prepare(stock("ble"), stock("ctrl"))
        assertEquals(NoNfcPatch.BLE_TARGET, NoNfcPatch.sha256(out.ble))
        assertEquals(NoNfcPatch.CTRL_TARGET, NoNfcPatch.sha256(out.ctrl))
        assertFalse(out.upToDate)
    }

    @Test
    fun targetsAreRecognisedAndLeftAlone() {
        val first = NoNfcPatch.prepare(stock("ble"), stock("ctrl"))
        val second = NoNfcPatch.prepare(first.ble, first.ctrl)
        assertTrue(second.upToDate)
        assertArrayEquals(first.ctrl, second.ctrl)
    }

    @Test
    fun aMismatchedPairIsRefused() {
        val first = NoNfcPatch.prepare(stock("ble"), stock("ctrl"))
        assertThrows(NoNfcPatch.Refused::class.java) { NoNfcPatch.prepare(first.ble, stock("ctrl")) }
        assertThrows(NoNfcPatch.Refused::class.java) { NoNfcPatch.prepare(stock("ble"), first.ctrl) }
    }

    @Test
    fun anythingElseIsRefused() {
        assertThrows(NoNfcPatch.Refused::class.java) {
            NoNfcPatch.prepare(ByteArray(1024), ByteArray(1024))
        }
    }
}
