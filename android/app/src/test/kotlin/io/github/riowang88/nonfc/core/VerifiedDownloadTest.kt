package io.github.riowang88.nonfc.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * [Orchestrator.verified] run by a real `sh`, with a `wget` that answers what the phone
 * could: the file, something else under a 200, or a failure. The string is the whole
 * defence on the device, so it is executed here rather than pattern-matched.
 */
class VerifiedDownloadTest {
    private val dir = Files.createTempDirectory("verified").toFile()
    private val dest = File(dir, "app_init.sh").apply { writeText("old\n") }
    private val good = "#!/bin/sh\n. /app/script/app_init.sh\n".toByteArray()

    /** Exit status of the command with `wget` writing `served` and exiting `status`. */
    private fun run(served: ByteArray, status: Int = 0): Int {
        assumeTrue(listOf("/usr/bin/md5sum", "/bin/md5sum", "/sbin/md5sum").any { File(it).exists() })
        val bin = File(dir, "bin").apply { mkdirs() }
        File(dir, "served").writeBytes(served)
        File(bin, "wget").apply {
            writeText("#!/bin/sh\ncp '${dir.path}/served' \"\$3\"\nexit $status\n")
            setExecutable(true)
        }
        val command = Orchestrator.verified("http://phone/x", dest.path, NoNfcPatch.md5(good))
        val process = ProcessBuilder("/bin/sh", "-c", command).apply {
            environment()["PATH"] = "${bin.path}:/usr/bin:/bin:/sbin"
        }.redirectErrorStream(true).start()
        process.inputStream.readBytes()
        return process.waitFor()
    }

    @Test
    fun theRightFileReplacesTheOldOne() {
        assertEquals(0, run(good))
        assertEquals(String(good), dest.readText())
        assertFalse(File(dest.path + ".new").exists())
    }

    /** What bricked a T6: a 200 whose body is not the file. */
    @Test
    fun theEmptyResultUnderA200NeverReachesTheName() {
        assertFalse(run("""{"result":{}}""".toByteArray()) == 0)
        assertEquals("old\n", dest.readText())
        assertFalse(File(dest.path + ".new").exists())
    }

    @Test
    fun aTruncatedFileNeverReachesTheName() {
        assertFalse(run(good.copyOf(good.size - 5)) == 0)
        assertEquals("old\n", dest.readText())
    }

    @Test
    fun aFailedDownloadNeverReachesTheName() {
        assertFalse(run(ByteArray(0), status = 1) == 0)
        assertEquals("old\n", dest.readText())
        assertFalse(File(dest.path + ".new").exists())
    }
}
