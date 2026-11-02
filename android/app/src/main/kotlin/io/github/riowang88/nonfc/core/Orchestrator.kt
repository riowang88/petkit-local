package io.github.riowang88.nonfc.core

import kotlinx.coroutines.delay
import java.security.SecureRandom

/** What the orchestrator needs from the phone's server and the device's temporary httpd. */
interface DeviceLink {
    val identity: DeviceApi.Identity?
    val signups: Int
    val lastRequestMs: Long
    val handedBackAtMs: Long

    fun enqueue(command: String)
    fun queueIsEmpty(): Boolean
    fun stage(name: String, data: ByteArray): String
    fun unstage(name: String)
    fun startHandBack(officialUrl: String)

    /** One GET against the device, or null if it did not answer. */
    suspend fun fetch(port: Int, name: String): ByteArray?
}

interface SshLink {
    /** Output of `command`, feeding it `stdin`. Throws if it cannot log in or it fails. */
    suspend fun exec(command: String, stdin: ByteArray? = null): String
}

/** The md5 of what must be running afterwards. Persisted, so a restart can still verify. */
data class Installed(val bleMd5: String, val ctrlMd5: String)

/**
 * The device side of `web/api/patchers.py`'s `no_nfc` branch, plus the SSH prerequisite,
 * the boot script merge and the hand-back. Section 5 of the plan is the specification.
 *
 * Every step that writes proves itself by reading back in the same command, through
 * [capture], rather than sleeping and hoping: the heartbeat has no delivery report. Nothing
 * reboots the device until the read-back matched.
 */
class Orchestrator(
    private val link: DeviceLink,
    private val ssh: SshLink,
    private val dropbear: () -> ByteArray,
    private val publicKey: String,
    private val prepare: (ByteArray, ByteArray) -> NoNfcPatch.Result = NoNfcPatch::prepare,
    private val log: (String) -> Unit,
) {
    private class Gate(val wrapper: String?, val patch: NoNfcPatch.Result)

    /** Read-only: does `run_cmd` execute, and is this the expected build? */
    suspend fun probe(): String =
        capture("id; uname -a; cat /proc/uptime; cat /proc/mounts; md5sum /app/bin/ble /app/bin/ctrl")
            ?: fail("设备没有回应：run_cmd 没执行，或手机访问不到设备")

    /**
     * Everything up to and including the reboot. Returns the md5 [verify] expects.
     * A device already on the target build only gets this phone's SSH key.
     */
    suspend fun install(): Installed {
        val gate = gate()
        ensureSsh(gate.wrapper)
        val md5 = Installed(NoNfcPatch.md5(gate.patch.ble), NoNfcPatch.md5(gate.patch.ctrl))
        if (gate.patch.upToDate) {
            if (!BootScript.hasNoNfc(gate.wrapper)) fail("程序已是目标版，但启动脚本没有挂载，状态不明，不动")
            log("程序已是目标版，不重复打补丁")
            return md5
        }

        val wrapper = readWrapper()
        val bytes = gate.patch.ble.size.toLong() + gate.patch.ctrl.size
        checkSpace(bytes)
        upload(gate.patch, md5)
        writeWrapper(wrapper, BootScript.withNoNfc(wrapper))
        rebootAndWait()
        return md5
    }

    /** The patched programs are the ones mounted, and the merge lost nothing. */
    suspend fun verify(md5: Installed) {
        val text = capture(
            "md5sum /app/bin/ble /app/bin/ctrl; echo $SPLIT; cat ${BootScript.PATH}; " +
                "echo $SPLIT; cat ${BootScript.BACKUP} 2>/dev/null",
        ) ?: fail("复查没有回应")
        link.enqueue("killall httpd 2>/dev/null")
        val (sums, now, before) = (text.split(SPLIT) + "" + "").take(3)
        if (md5.bleMd5 !in sums || md5.ctrlMd5 !in sums) fail("运行中的 ble/ctrl 不是打好的文件")
        if (!BootScript.hasNoNfc(now)) fail("启动脚本没有挂载补丁")
        val lost = BootScript.lost(before, now)
        if (lost.isNotEmpty()) fail("启动脚本丢了原有内容：$lost")
        log("复查通过：ble、ctrl 是打好的文件，启动脚本保留了原有内容")
    }

    /**
     * Point the device back at PetKit. `dev_signup` never touched its secret, so only the
     * address and the IoT block have to change, and they take two boots: the IoT call comes
     * before service discovery, so the first boot still reads ours and the second, which
     * only SSH can trigger once the device has left, reads PetKit's.
     */
    suspend fun handBack(officialUrl: String, md5: Installed?) {
        ssh.exec("true")
        link.startHandBack(officialUrl)
        val since = System.currentTimeMillis()
        log("服务发现已切到 $officialUrl，重启设备")
        link.enqueue("killall httpd 2>/dev/null")
        link.enqueue("reboot")
        waitFor(BOOT_ATTEMPTS, "设备重启后没有来取官方地址") { link.handedBackAtMs >= since }

        // config_save has run by the time the reply is parsed; this is for the boot to settle.
        delay(SETTLE_MS)
        log("设备已取走官方地址，经 SSH 再重启一次取回官方 IoT")
        val secondBoot = System.currentTimeMillis()
        ssh.exec(DETACHED_REBOOT)
        delay(SECOND_BOOT_MS)

        if (link.lastRequestMs >= secondBoot) fail("设备重启后又来找手机，交还没有生效")
        if (md5 != null) {
            val sums = ssh.exec("md5sum /app/bin/ble /app/bin/ctrl")
            if (md5.bleMd5 !in sums || md5.ctrlMd5 !in sums) fail("交还后补丁不在了")
        }
        val mqtt = ssh.exec("cat /proc/net/tcp").lines().any { ":22B3 01" in it }
        log(if (mqtt) "设备已连上 8883（官方 MQTT）" else "暂未看到 8883 连接，请到官方 App 确认设备在线")
    }

    /**
     * Unmount and delete, over SSH because this usually runs after the device has left.
     * The wrapper is rewritten before the files go, so it never names a missing file.
     */
    suspend fun remove(alsoSsh: Boolean) {
        val text = ssh.exec("cat ${BootScript.PATH}")
        if (BootScript.kind(text) != BootScript.Kind.OURS) fail("启动脚本不认识，不改")
        var next = BootScript.withoutNoNfc(text)
        if (alsoSsh) next = BootScript.withoutSsh(next)
        val p = BootScript.PATH
        if (BootScript.isBare(next)) {
            ssh.exec("rm -f $p")
        } else {
            val sum = ssh.exec("cat > $p.new && chmod +x $p.new && mv -f $p.new $p && md5sum $p", next.toByteArray())
            if (NoNfcPatch.md5(next.toByteArray()) !in sum) fail("启动脚本回读不符，不重启")
        }
        val s = BootScript.STORE
        var files = "$s/${NoNfcPatch.BLE_NAME} $s/${NoNfcPatch.CTRL_NAME} ${BootScript.BACKUP}"
        if (alsoSsh) files += " $s/dropbear $s/dbkey_ecdsa $s/authorized_keys"
        ssh.exec("rm -f $files")
        ssh.exec(DETACHED_REBOOT)
        log("已卸载，设备重启中")
        if (alsoSsh) return
        delay(SECOND_BOOT_MS)
        val sums = ssh.exec("md5sum /app/bin/ble /app/bin/ctrl")
        if (STOCK_BLE_MD5 !in sums || STOCK_CTRL_MD5 !in sums) fail("重启后不是原厂程序")
        log("已回到原厂 ble、ctrl")
    }

    /** No writes above this line of defence: identity, boot script, then the whole-file SHA. */
    private suspend fun gate(): Gate {
        val id = link.identity ?: fail("设备还没上线")
        if (!id.type.equals("t6", true) || id.firmware !in FIRMWARES) {
            throw NoNfcPatch.Refused("不支持：型号 ${id.type}，固件 ${id.firmware}。只支持 T6 固件 1.951")
        }
        val storage = capture("[ -f /system/user.conf ] && echo STORAGE /system; [ -f /opt/user.conf ] && echo STORAGE /opt")
            ?: fail("读不到存储目录")
        if ("STORAGE /system" !in storage) fail("存储目录不是 /system：$storage")

        val wrapper = readWrapper()
        if (BootScript.kind(wrapper) == BootScript.Kind.FOREIGN) {
            throw NoNfcPatch.Refused("启动脚本不是 petkit-local 的包装脚本，不改")
        }
        if (BootScript.hasMqtt(wrapper)) throw NoNfcPatch.Refused("已装 MQTT 补丁，两个都替换 ctrl，不能共存")
        BootScript.otherMounts(wrapper).forEach { log("注意：启动脚本还有其他挂载，会保留并跟着回官方：$it") }

        log("下载设备上的 ble、ctrl 核对 SHA")
        link.enqueue("busybox httpd -p $HTTPD_PORT -h /app/bin </dev/null >/dev/null 2>&1 &")
        try {
            val ble = fetchRetrying("ble")
            val ctrl = fetchRetrying("ctrl")
            return Gate(wrapper, prepare(ble, ctrl))
        } finally {
            link.enqueue("killall httpd 2>/dev/null")
        }
    }

    private suspend fun ensureSsh(wrapper: String?) {
        val tag = publicKey.trim().substringAfterLast(' ')
        val keyFile = (publicKey.trim() + "\n").toByteArray()
        val url = link.stage(KEY_NAME, keyFile)
        val s = BootScript.STORE
        try {
            if (BootScript.hasSsh(wrapper)) {
                log("设备已有 SSH，加入这台手机的公钥")
                val done = capture(
                    "wget -q -O /tmp/$KEY_NAME $url && mkdir -p /tmp/.ssh && " +
                        "for f in $s/authorized_keys /tmp/.ssh/authorized_keys; do " +
                        "grep -q '$tag' \$f 2>/dev/null || cat /tmp/$KEY_NAME >> \$f; done; " +
                        "rm -f /tmp/$KEY_NAME; echo $OK",
                )
                if (done == null || OK !in done) fail("写入公钥没有回应")
                ssh.exec("true")
            } else {
                log("设备没有 SSH，先装 SSH 补丁（多一次重启）")
                val binary = dropbear()
                checkSpace(binary.size.toLong() + keyFile.size + DBKEY_RESERVE)
                installDropbear(binary, url)
                writeWrapper(wrapper, BootScript.withSsh(wrapper))
                rebootAndWait()
                ssh.exec("true")
            }
        } finally {
            link.unstage(KEY_NAME)
        }
        log("手机能登录设备 SSH")
    }

    /** `patchers/ssh.py::build_install_commands`, minus starting it: a reboot follows. */
    private suspend fun installDropbear(binary: ByteArray, keyUrl: String) {
        val s = BootScript.STORE
        val url = link.stage("dropbear", binary)
        try {
            val out = capture(
                "[ -f $s/test_case_root ] && mv $s/test_case_root $s/old_test_case_root; " +
                    "wget -q -O $s/dropbear.new $url && chmod +x $s/dropbear.new && " +
                    "mv -f $s/dropbear.new $s/dropbear && " +
                    "wget -q -O $s/authorized_keys $keyUrl && " +
                    "{ [ -f $s/dbkey_ecdsa ] || { cp $s/dropbear /tmp/dropbearkey && " +
                    "/tmp/dropbearkey -t ecdsa -f $s/dbkey_ecdsa && rm -f /tmp/dropbearkey; }; } && " +
                    "echo $OK; md5sum $s/dropbear",
                UPLOAD_ATTEMPTS,
            )
            if (out == null || OK !in out || NoNfcPatch.md5(binary) !in out) {
                fail("dropbear 安装回读不符，不重启：$out")
            }
        } finally {
            link.unstage("dropbear")
        }
    }

    private suspend fun upload(patch: NoNfcPatch.Result, md5: Installed) {
        val ble = "${BootScript.STORE}/${NoNfcPatch.BLE_NAME}"
        val ctrl = "${BootScript.STORE}/${NoNfcPatch.CTRL_NAME}"
        val bleUrl = link.stage(NoNfcPatch.BLE_NAME, patch.ble)
        val ctrlUrl = link.stage(NoNfcPatch.CTRL_NAME, patch.ctrl)
        log("把打好的 ble、ctrl 传回设备")
        try {
            // A running program cannot be opened for writing, so each file lands beside
            // itself and replaces the name; the old copy runs until the reboot.
            val out = capture(
                "wget -q -O $ble.new $bleUrl && chmod +x $ble.new && mv -f $ble.new $ble && " +
                    "wget -q -O $ctrl.new $ctrlUrl && chmod +x $ctrl.new && mv -f $ctrl.new $ctrl; " +
                    "md5sum $ble $ctrl",
                UPLOAD_ATTEMPTS,
            )
            if (out == null || md5.bleMd5 !in out || md5.ctrlMd5 !in out) {
                fail("传回的 ble/ctrl 回读不符，不重启：$out")
            }
        } finally {
            link.unstage(NoNfcPatch.BLE_NAME)
            link.unstage(NoNfcPatch.CTRL_NAME)
        }
    }

    private suspend fun readWrapper(): String? {
        val p = BootScript.PATH
        val text = capture("if [ -f $p ]; then echo $PRESENT; cat $p; fi") ?: fail("读不到启动脚本")
        return if (PRESENT in text) text.substringAfter("$PRESENT\n", "") else null
    }

    /** The add-on reboots even when this step times out. A timeout here means no reboot. */
    private suspend fun writeWrapper(old: String?, next: String) {
        val lost = if (old == null) emptyList() else BootScript.lost(old, next)
        if (lost.isNotEmpty()) fail("合并启动脚本会丢内容：$lost")
        val p = BootScript.PATH
        val bytes = next.toByteArray()
        val url = link.stage("app_init.sh", bytes)
        try {
            val out = capture(
                "if [ -f $p ] && [ ! -f ${BootScript.BACKUP} ]; then cp $p ${BootScript.BACKUP}; fi; " +
                    "wget -q -O $p.new $url && chmod +x $p.new && mv -f $p.new $p; md5sum $p",
            )
            if (out == null || NoNfcPatch.md5(bytes) !in out) fail("启动脚本回读不符，不重启：$out")
        } finally {
            link.unstage("app_init.sh")
        }
        log("启动脚本已合并")
    }

    private suspend fun checkSpace(writeBytes: Long) {
        val p = BootScript.PATH
        val text = capture("df -k ${BootScript.STORE}; wc -c $p") ?: fail("读不到可用空间")
        val free = Shell.availableBytes(text, BootScript.STORE) ?: fail("解析不了可用空间：$text")
        val existing = Shell.sizes(text)[p] ?: 0L
        val needed = maxOf(0L, writeBytes - existing) + WRAPPER_RESERVE + SPACE_MARGIN
        if (free < needed) throw NoNfcPatch.Refused("/system 只剩 $free 字节，需要 $needed")
        log("/system 可用 $free 字节，需要 $needed")
    }

    private suspend fun rebootAndWait() {
        val boots = link.signups
        link.enqueue("killall httpd 2>/dev/null")
        link.enqueue("reboot")
        log("设备重启中，不要退出")
        waitFor(BOOT_ATTEMPTS, "设备重启后没有回到手机") { link.signups > boots }
        waitFor(BOOT_ATTEMPTS, "设备重启后心跳没有恢复") { link.queueIsEmpty() }
    }

    /**
     * Run a command and read back what it printed. Output goes to a one-time name in /tmp
     * and is renamed before the httpd starts, so a 200 can only ever be a complete file,
     * and a sentinel from this call rules out a leftover (`common.py::run_cmd_capture`).
     *
     * The httpd is backgrounded with its fds sent to /dev/null. The firmware runs each
     * run_cmd through a pipe it reads; a foreground `busybox httpd` inherits that pipe, never
     * daemonises cleanly, and was seen on a live T6 stuck in R never binding the port — so the
     * phone got ConnectException while the device looked like it had "not answered". Detaching
     * the fds lets the firmware's run_cmd return and the httpd actually listen.
     */
    private suspend fun capture(command: String, attempts: Int = CAPTURE_ATTEMPTS): String? {
        val nonce = ByteArray(8).also(SecureRandom()::nextBytes).joinToString("") { "%02x".format(it) }
        val name = "pk_$nonce"
        val sentinel = "__PK_END_$nonce"
        val out = "/tmp/$name"
        link.enqueue(
            "{ $command; } > $out.part 2>&1; echo $sentinel >> $out.part; " +
                "mv $out.part $out; busybox httpd -p $PROBE_PORT -h /tmp </dev/null >/dev/null 2>&1 &",
        )
        try {
            repeat(attempts) {
                delay(POLL_MS)
                val text = link.fetch(PROBE_PORT, name)?.toString(Charsets.UTF_8)?.trimEnd()
                if (text != null && text.endsWith(sentinel)) {
                    return text.removeSuffix(sentinel).trimEnd('\n')
                }
            }
            return null
        } finally {
            link.enqueue("rm -f $out $out.part")
        }
    }

    private suspend fun fetchRetrying(name: String): ByteArray {
        repeat(CAPTURE_ATTEMPTS) {
            delay(POLL_MS)
            link.fetch(HTTPD_PORT, name)?.let { return it }
        }
        fail("下载不到设备上的 $name")
    }

    private suspend fun waitFor(attempts: Int, failure: String, done: () -> Boolean) {
        repeat(attempts) {
            if (done()) return
            delay(POLL_MS)
        }
        if (!done()) fail(failure)
    }

    private fun fail(message: String): Nothing = throw IllegalStateException(message)

    companion object {
        const val STOCK_BLE_MD5 = "93b032945de7915c7cb99b4eda3aabbe"
        const val STOCK_CTRL_MD5 = "9b204541c21236abb78d84369565a26d"

        private val FIRMWARES = setOf("951", "1.951")
        private const val HTTPD_PORT = 8888
        private const val PROBE_PORT = 8889
        private const val KEY_NAME = "authorized_keys"
        private const val SPLIT = "__PK_SPLIT__"
        private const val PRESENT = "__PK_PRESENT__"
        private const val OK = "__PK_OK__"
        private const val DETACHED_REBOOT = "(sleep 1; reboot) >/dev/null 2>&1 &"

        private const val POLL_MS = 3_000L
        private const val CAPTURE_ATTEMPTS = 30
        private const val UPLOAD_ATTEMPTS = 60
        private const val BOOT_ATTEMPTS = 100
        private const val SETTLE_MS = 30_000L
        private const val SECOND_BOOT_MS = 120_000L

        private const val DBKEY_RESERVE = 4096L
        private const val WRAPPER_RESERVE = 4096L
        private const val SPACE_MARGIN = 65536L
    }
}
