package io.github.riowang88.nonfc.core

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A scripted T6: it "executes" queued shell by pattern and serves the captured output the
 * way busybox httpd would, so the whole sequence runs with no device.
 */
private class FakeDevice(var wrapper: String?) : DeviceLink, SshLink {
    override var identity: DeviceApi.Identity? = DeviceApi.Identity("t6", "951")
    override var signups = 1
    override var lastRequestMs = 0L
    override var handedBackAtMs = 0L

    val commands = mutableListOf<String>()
    val ssh = mutableListOf<String>()
    val stagedNames = mutableSetOf<String>()
    private val staged = mutableMapOf<String, ByteArray>()
    private val tmp = mutableMapOf<String, String>()
    var freeKb = 5023L
    var corruptUpload = false

    /** A staged name the phone answers 404 for, as it does once the file is unstaged. */
    var missing: String? = null
    private val undelivered = mutableSetOf<String>()
    var cancelled = 0
    var restartsEatingCommands = 0
    var reachable = true
    var sshUp = true
    var officialUrl: String? = null

    /** Files on /system, as bytes, for the read-backs. */
    val files = mutableMapOf<String, ByteArray>()

    val rebooted get() = commands.count { it == "reboot" }

    override fun enqueue(command: String) {
        commands += command
        if (command == "reboot") {
            signups++
            if (officialUrl != null) handedBackAtMs = System.currentTimeMillis()
            return
        }
        val inner = Regex("""^\{ (.*); } > /tmp/(pk_\w+)\.part""").find(command) ?: return
        if (restartsEatingCommands > 0) {
            // `ctrl` restarted right after taking it: nothing ran, and the device signed up again.
            restartsEatingCommands--
            signups++
            return
        }
        if (!reachable) {
            undelivered += command
            return
        }
        val sentinel = Regex("""echo (__PK_END_\w+)""").find(command)!!.groupValues[1]
        tmp[inner.groupValues[2]] = run(inner.groupValues[1]) + "\n" + sentinel + "\n"
    }

    override fun cancel(command: String) = undelivered.remove(command).also { if (it) cancelled++ }

    /**
     * busybox for the downloads: fetch, compare the md5, and only then let the file take
     * its name. A chain stops at the first one that fails, as `&&` does.
     */
    private fun run(shell: String): String {
        val out = StringBuilder()
        if ("cp ${BootScript.PATH} " in shell) {
            wrapper?.let { files.getOrPut(BootScript.BACKUP) { it.toByteArray() } }
        }
        var landed = true
        for (match in DOWNLOAD.findAll(shell)) {
            val (target, url, md5) = match.destructured
            val name = url.substringAfterLast('/')
            var data = staged[name].takeUnless { name == missing }
            if (data != null && corruptUpload && name == NoNfcPatch.BLE_NAME) data += 0
            if (data == null || NoNfcPatch.md5(data) != md5) {
                landed = false
                break
            }
            val dest = target.removeSuffix(".new")
            files[dest] = data
            if (dest == BootScript.PATH) wrapper = String(data)
        }
        if ("__PK_OK__" in shell && landed) out.append("__PK_OK__\n")
        if ("STORAGE" in shell) out.append("STORAGE /system\n")
        if ("__PK_PRESENT__" in shell) wrapper?.let { out.append("__PK_PRESENT__\n").append(it) }
        if (shell.startsWith("df -k")) {
            out.append("Filesystem 1K-blocks Used Available Use% Mounted on\n")
            out.append("/dev/mtdblock7 16384 1000 $freeKb 9% /system\n")
        }
        Regex("""md5sum ((?:/[^\s;]+ ?)+)""").find(shell.replace(GATE, ""))?.let { match ->
            match.groupValues[1].trim().split(" ").forEach { path ->
                val name = path.replace("/app/bin/ble", "/system/ble_patched")
                    .replace("/app/bin/ctrl", "/system/ctrl_nonfc")
                files[name]?.let { out.append(NoNfcPatch.md5(it)).append("  ").append(path).append("\n") }
            }
        }
        if ("__PK_SPLIT__" in shell) {
            out.append("__PK_SPLIT__\n").append(wrapper).append("\n__PK_SPLIT__\n")
            files[BootScript.BACKUP]?.let { out.append(String(it)) }
        }
        return out.toString()
    }

    override fun queueIsEmpty() = true

    override fun stage(name: String, data: ByteArray): String {
        staged[name] = data
        stagedNames += name
        return "http://phone/patcher/t/$name"
    }

    override fun unstage(name: String) { staged.remove(name) }

    override fun startHandBack(officialUrl: String) { this.officialUrl = officialUrl }

    override suspend fun fetch(port: Int, name: String): ByteArray? = when (port) {
        8888 -> byteArrayOf(0x7F, 0x45)
        else -> tmp[name]?.toByteArray()
    }

    private companion object {
        val GATE = Regex("""md5sum \S+ \| grep -q \w+""")
        val DOWNLOAD = Regex("""wget -q -O (\S+) (\S+) && md5sum \S+ \| grep -q (\w+)""")
    }

    override suspend fun exec(command: String, stdin: ByteArray?): String {
        if (!sshUp) error("connection refused")
        ssh += command
        return when {
            command == "cat ${BootScript.PATH}" -> wrapper.orEmpty()
            command.startsWith("cat > ") -> {
                wrapper = String(stdin!!)
                NoNfcPatch.md5(stdin) + "  " + BootScript.PATH
            }
            command.startsWith("md5sum") -> files.values.joinToString("\n") { NoNfcPatch.md5(it) } +
                "\n" + Orchestrator.STOCK_BLE_MD5 + "\n" + Orchestrator.STOCK_CTRL_MD5
            else -> ""
        }
    }
}

class OrchestratorTest {
    private val ble = byteArrayOf(1, 2, 3)
    private val ctrl = byteArrayOf(4, 5, 6)
    private val out = mutableListOf<String>()
    private val withSsh = BootScript.withSsh(null)

    private fun orchestrator(device: FakeDevice, upToDate: Boolean = false) = Orchestrator(
        link = device,
        ssh = device,
        dropbear = { byteArrayOf(9, 9) },
        publicKey = "ecdsa-sha2-nistp256 AAAA t6-nonfc-1",
        prepare = { _, _ -> NoNfcPatch.Result(ble, ctrl, upToDate) },
        log = { out += it },
    )

    @Test
    fun installsWithExistingSshAndKeepsTheWrapper() = runTest {
        val device = FakeDevice(withSsh)
        val md5 = orchestrator(device).install()
        assertEquals(1, device.rebooted)
        assertTrue(BootScript.hasNoNfc(device.wrapper))
        assertTrue(BootScript.hasSsh(device.wrapper))
        assertEquals(NoNfcPatch.md5(ble), md5.bleMd5)
        orchestrator(device).verify(md5)
    }

    @Test
    fun aCommandLostToADeviceRestartIsSentAgain() = runTest {
        val device = FakeDevice(withSsh)
        device.restartsEatingCommands = 3
        val md5 = orchestrator(device).install()
        assertTrue(BootScript.hasNoNfc(device.wrapper))
        assertEquals(NoNfcPatch.md5(ctrl), md5.ctrlMd5)
        assertTrue(out.count { "重发" in it } >= 3)
    }

    @Test
    fun aDeviceWithoutSshGetsItFirstAndRebootsTwice() = runTest {
        val device = FakeDevice(null)
        orchestrator(device).install()
        assertEquals(2, device.rebooted)
        assertTrue(BootScript.hasSsh(device.wrapper))
        assertTrue(device.commands.indexOfFirst { "dropbear.new" in it } <
            device.commands.indexOfFirst { "ble_patched.new" in it })
    }

    @Test
    fun noSshLoginMeansCtrlIsNeverTouched() = runTest {
        val device = FakeDevice(null).apply { sshUp = false }
        val error = runCatching { orchestrator(device).install() }.exceptionOrNull()
        assertTrue(error != null)
        assertFalse(device.commands.any { "ble_patched.new" in it })
    }

    @Test
    fun aBadReadBackNeverReboots() = runTest {
        val device = FakeDevice(withSsh).apply { corruptUpload = true }
        val error = runCatching { orchestrator(device).install() }.exceptionOrNull()
        assertTrue(error!!.message!!.contains("不重启"))
        assertEquals(0, device.rebooted)
        assertEquals(withSsh, device.wrapper)
        assertFalse("/system/ble_patched" in device.files)
    }

    /** The brick: the wrapper's download misses, and the 13-byte answer became the script. */
    @Test
    fun aMissedWrapperDownloadLeavesTheBootScriptAlone() = runTest {
        val device = FakeDevice(withSsh).apply { missing = "app_init.sh" }
        val error = runCatching { orchestrator(device).install() }.exceptionOrNull()
        assertTrue(error!!.message!!.contains("不重启"))
        assertEquals(0, device.rebooted)
        assertEquals(withSsh, device.wrapper)
    }

    @Test
    fun aMissedDropbearDownloadInstallsNothing() = runTest {
        val device = FakeDevice(null).apply { missing = "dropbear" }
        val error = runCatching { orchestrator(device).install() }.exceptionOrNull()
        assertTrue(error!!.message!!.contains("不重启"))
        assertEquals(0, device.rebooted)
        assertEquals(null, device.wrapper)
        assertFalse("/system/dropbear" in device.files)
        assertFalse("/system/authorized_keys" in device.files)
    }

    /** No download may reach the name a file runs under before its md5 was compared. */
    @Test
    fun everyDownloadIsVerifiedBeforeItIsMoved() = runTest {
        val device = FakeDevice(null)
        orchestrator(device).install()
        val downloads = device.commands.filter { "wget" in it }
        assertTrue(downloads.size >= 4)
        downloads.forEach { command ->
            Regex("""wget -q -O (\S+)""").findAll(command).forEach { match ->
                val target = match.groupValues[1]
                assertTrue(target, target.endsWith(".new") || target.startsWith("/tmp/"))
                val rest = command.substring(match.range.last)
                val gate = rest.indexOf("md5sum $target | grep -q")
                val move = rest.indexOf("mv -f $target").takeIf { it >= 0 } ?: rest.indexOf("cat $target")
                assertTrue(command, gate in 0 until move)
            }
        }
    }

    @Test
    fun aDeviceThatStopsAnsweringIsNeverRebooted() = runTest {
        val device = FakeDevice(withSsh).apply { reachable = false }
        assertTrue(runCatching { orchestrator(device).install() }.isFailure)
        assertEquals(0, device.rebooted)
        // What it never collected is withdrawn, not left to run after the files are gone.
        assertTrue(device.cancelled > 0)
    }

    @Test
    fun theWrongModelIsRefusedBeforeAnyCommand() = runTest {
        val device = FakeDevice(withSsh).apply { identity = DeviceApi.Identity("t5", "951") }
        val error = runCatching { orchestrator(device).install() }.exceptionOrNull()
        assertTrue(error is NoNfcPatch.Refused)
        assertTrue(device.commands.isEmpty())
    }

    @Test
    fun aForeignWrapperIsRefusedBeforeAnyWrite() = runTest {
        val device = FakeDevice("#!/bin/sh\nmine\n")
        assertTrue(runCatching { orchestrator(device).install() }.exceptionOrNull() is NoNfcPatch.Refused)
        assertFalse(device.commands.any { "wget" in it })
    }

    @Test
    fun theMqttPatchIsRefused() = runTest {
        val device = FakeDevice(withSsh.replace("mkdir", "mount --bind /system/ctrl_patched /app/bin/ctrl\nmkdir"))
        assertTrue(runCatching { orchestrator(device).install() }.exceptionOrNull() is NoNfcPatch.Refused)
    }

    @Test
    fun notEnoughSpaceWritesNothing() = runTest {
        val big = NoNfcPatch.Result(ByteArray(4 * 1024 * 1024), ctrl, false)
        val device = FakeDevice(withSsh).apply { freeKb = 100 }
        val orchestrator = Orchestrator(device, device, { byteArrayOf() }, "k k k", { _, _ -> big }, { })
        assertTrue(runCatching { orchestrator.install() }.exceptionOrNull() is NoNfcPatch.Refused)
        assertFalse(device.commands.any { "ble_patched.new" in it })
    }

    @Test
    fun anUpToDateDeviceOnlyGetsTheKey() = runTest {
        val device = FakeDevice(BootScript.withNoNfc(withSsh))
        orchestrator(device, upToDate = true).install()
        assertEquals(0, device.rebooted)
        assertTrue(device.commands.any { "authorized_keys" in it })
        assertFalse(device.commands.any { "ble_patched.new" in it })
    }

    @Test
    fun handBackRebootsTwiceAndTheSecondIsOverSsh() = runTest {
        val device = FakeDevice(withSsh)
        val md5 = orchestrator(device).install()
        orchestrator(device).handBack("https://api.petktasia.com/6/", md5)
        assertEquals("https://api.petktasia.com/6/", device.officialUrl)
        assertEquals(2, device.rebooted)
        assertTrue(device.ssh.any { "reboot" in it })
    }

    @Test
    fun handBackWillNotStartWithoutSsh() = runTest {
        val device = FakeDevice(withSsh).apply { sshUp = false }
        assertThrows(IllegalStateException::class.java) {
            kotlinx.coroutines.runBlocking { orchestrator(device).handBack("https://x/6/", null) }
        }
        assertEquals(null, device.officialUrl)
    }

    @Test
    fun removalRewritesTheWrapperBeforeDeleting() = runTest {
        val device = FakeDevice(BootScript.withNoNfc(withSsh))
        orchestrator(device).remove(alsoSsh = false)
        assertFalse(BootScript.hasNoNfc(device.wrapper))
        assertTrue(BootScript.hasSsh(device.wrapper))
        assertTrue(device.ssh.indexOfFirst { it.startsWith("cat > ") } <
            device.ssh.indexOfFirst { it.startsWith("rm -f") })
    }

    @Test
    fun removingEverythingDeletesABareWrapper() = runTest {
        val device = FakeDevice(BootScript.withNoNfc(withSsh))
        orchestrator(device).remove(alsoSsh = true)
        assertTrue(device.ssh.any { it == "rm -f ${BootScript.PATH}" })
        assertTrue(device.ssh.any { "authorized_keys" in it })
    }
}
