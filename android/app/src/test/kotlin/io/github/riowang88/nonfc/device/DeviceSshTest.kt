package io.github.riowang88.nonfc.device

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class DeviceSshTest {
    @get:Rule val folder = TemporaryFolder()

    @Test
    fun aKeyIsMadeOnceInAuthorizedKeysFormat() {
        val first = SshKeys(folder.root).publicKey
        assertTrue(first.startsWith("ecdsa-sha2-nistp256 "))
        assertEquals(3, first.split(" ").size)
        assertEquals(first, SshKeys(folder.root).publicKey)
    }

    /**
     * A real login: NONFC_SSHD_PORT names a local sshd reading its keys from
     * NONFC_SSHD_KEYS, into which this test writes the generated public key.
     */
    @Test
    fun logsInRunsCommandsAndFeedsStdin() = runBlocking<Unit> {
        val port = System.getenv("NONFC_SSHD_PORT")?.toIntOrNull()
        assumeTrue("NONFC_SSHD_PORT not set", port != null)
        val keys = SshKeys(folder.root)
        File(System.getenv("NONFC_SSHD_KEYS")).writeText(keys.publicKey + "\n")
        val ssh = DeviceSsh(keys, System.getProperty("user.name"), port!!) { "127.0.0.1" }

        assertEquals("hello\n", ssh.exec("echo hello"))
        assertEquals("from stdin", ssh.exec("cat", "from stdin".toByteArray()))
        assertTrue(ssh.exec("(sleep 1; true) >/dev/null 2>&1 &").isEmpty())
        assertThrows(IllegalStateException::class.java) { runBlocking { ssh.exec("exit 3") } }
    }
}
