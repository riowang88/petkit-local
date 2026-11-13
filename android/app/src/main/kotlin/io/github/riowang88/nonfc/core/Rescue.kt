package io.github.riowang88.nonfc.core

/**
 * Getting a device back whose boot script no longer starts the stock init, over the SSH
 * it may still run. The script goes aside under a new name rather than being deleted:
 * what it held is the evidence of how it got there.
 */
object Rescue {
    const val PRESENT = "__PK_PRESENT__"

    /** Read-only: the script and its siblings, byte by byte when it exists. */
    val READ = "ls -l ${BootScript.PATH}*; if [ -f ${BootScript.PATH} ]; then echo $PRESENT; " +
        "cat ${BootScript.PATH}; fi"

    /**
     * Move the script aside and reboot, so the firmware falls back to the stock init.
     * The name carries the time so an earlier rescue's copy is never overwritten.
     */
    val MOVE_ASIDE = "[ -f ${BootScript.PATH} ] && mv ${BootScript.PATH} ${BootScript.PATH}.bad.\$(date +%s) " +
        "&& sync && echo MOVED && { (sleep 1; reboot) >/dev/null 2>&1 & }"

    enum class Verdict { MISSING, HEALTHY, NO_STOCK_INIT, FOREIGN }

    /** The script from [READ]'s output, or null when there is none. */
    fun script(output: String): String? =
        if (PRESENT in output) output.substringAfter("$PRESENT\n", "") else null

    fun verdict(script: String?): Verdict = when {
        script == null -> Verdict.MISSING
        !BootScript.startsStock(script) -> Verdict.NO_STOCK_INIT
        BootScript.kind(script) == BootScript.Kind.OURS -> Verdict.HEALTHY
        else -> Verdict.FOREIGN
    }

    fun describe(verdict: Verdict): String = when (verdict) {
        Verdict.MISSING -> "没有启动脚本：设备走原厂启动，不需要救援"
        Verdict.HEALTHY -> "启动脚本是正常的 petkit-local 包装。改名会让 SSH 和补丁在下次开机时失效，没有必要"
        Verdict.NO_STOCK_INIT -> "启动脚本不启动原厂程序，这就是开机后屏幕和按键都没反应的原因。改名并重启即可恢复"
        Verdict.FOREIGN -> "启动脚本不是 petkit-local 写的，但会启动原厂程序。先弄清是谁写的，再决定要不要改名"
    }

    /** Port 22 open, closed, or silent: a refusal proves the host is up with no sshd. */
    enum class Port { OPEN, REFUSED, TIMEOUT }

    fun describe(reachable: Boolean, port: Port): String {
        val host = if (reachable) "ping 通" else "ping 不通"
        return when (port) {
            Port.OPEN -> "$host，22 端口开着：可以用 SSH 救援"
            Port.REFUSED -> "$host，22 端口拒绝连接：设备在线，但 SSH 没在运行，只能接串口"
            Port.TIMEOUT -> if (reachable) {
                "ping 通，22 端口没有回应：SSH 没在运行或被拦，只能接串口"
            } else {
                "ping 不通，22 端口也没有回应：设备不在这个网络上（IP 不对，或 Wi-Fi 没起来），只能接串口"
            }
        }
    }
}
