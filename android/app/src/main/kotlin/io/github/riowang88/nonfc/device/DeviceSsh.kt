package io.github.riowang88.nonfc.device

import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.JSch
import com.jcraft.jsch.KeyPair
import io.github.riowang88.nonfc.core.SshLink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * The phone's own ECDSA key: generated on first run, private key in app-private storage.
 * It is the only way back into a device once it has left for PetKit's cloud, so it goes
 * with the phone. [privateKeyPem] is what the export button writes out.
 */
class SshKeys(dir: File) {
    private val private = File(dir, "ssh_ecdsa")
    private val public = File(dir, "ssh_ecdsa.pub")

    init {
        if (!private.exists()) {
            val pair = KeyPair.genKeyPair(JSch(), KeyPair.ECDSA, 256)
            private.outputStream().use { pair.writePrivateKey(it) }
            public.outputStream().use { pair.writePublicKey(it, "t6-nonfc-${System.currentTimeMillis()}") }
            pair.dispose()
        }
    }

    val publicKey get() = public.readText().trim()
    val privateKeyPem get() = private.readBytes()
    val privatePath: String get() = private.path
}

class DeviceSsh(
    private val keys: SshKeys,
    private val user: String = "root",
    private val port: Int = 22,
    private val host: () -> String?,
) : SshLink {
    override suspend fun exec(command: String, stdin: ByteArray?): String = withContext(Dispatchers.IO) {
        val address = host() ?: error("不知道设备 IP")
        val jsch = JSch().apply { addIdentity(keys.privatePath) }
        val session = jsch.getSession(user, address, port).apply {
            // The device's host key is generated on it and known to nobody in advance.
            setConfig("StrictHostKeyChecking", "no")
            setConfig("PreferredAuthentications", "publickey")
            connect(CONNECT_MS)
        }
        try {
            val channel = session.openChannel("exec") as ChannelExec
            val output = ByteArrayOutputStream()
            channel.setCommand(command)
            channel.outputStream = output
            channel.setErrStream(output)
            channel.inputStream = stdin?.inputStream()
            channel.connect(CONNECT_MS)
            val deadline = System.currentTimeMillis() + EXEC_MS
            while (!channel.isClosed && System.currentTimeMillis() < deadline) Thread.sleep(100)
            val status = channel.exitStatus
            channel.disconnect()
            val text = String(output.toByteArray())
            check(status == 0) { "SSH 命令失败（$status）：$text" }
            text
        } finally {
            session.disconnect()
        }
    }

    private companion object {
        const val CONNECT_MS = 10_000
        const val EXEC_MS = 30_000L
    }
}
