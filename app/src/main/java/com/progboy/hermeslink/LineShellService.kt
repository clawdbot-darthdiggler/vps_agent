package com.progboy.hermeslink

import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.ChannelShell
import kotlinx.coroutines.*
import java.io.OutputStream

/** Transport owner for the deliberately line-oriented, non-PTY terminal. */
class LineShellService {
    suspend fun sendLine(sink: OutputStream, line: String) = withContext(Dispatchers.IO) {
        require(line.none { it == '\n' || it == '\r' || it == '\u0000' })
        sink.write((line + "\n").toByteArray(Charsets.UTF_8)); sink.flush()
    }
    suspend fun run(profile: AgentProfile, docker: Boolean, operation: ConnectionOperation,
        connected: suspend (OutputStream) -> Unit, output: suspend (String) -> Unit) = withContext(Dispatchers.IO) {
        val command = if (docker) ProfilePolicy.dockerCommand(profile.container)
            ?: throw ConnectionFailure(DiagnosticStage.VALIDATION, DiagnosticCode.INVALID) else null
        try {
            val ssh = ConnectionService().connect(profile, operation)
            ensureActive(); operation.check()
            val channel: com.jcraft.jsch.Channel = if (docker) (ssh.openChannel("exec") as ChannelExec).apply { setCommand(command!!); setPty(false) }
                else (ssh.openChannel("shell") as ChannelShell).apply { setPty(false) }
            operation.own { channel.disconnect() }
            val sources = listOf(channel.inputStream to StreamTextDecoder(), channel.extInputStream to StreamTextDecoder())
            val sink = channel.outputStream
            channel.connect(4000)
            ensureActive(); operation.check(); connected(sink)
            val bytes = ByteArray(2048)
            while (isActive) {
                operation.check()
                var read = false
                for ((stream, decoder) in sources) {
                    val available = stream.available()
                    if (available > 0) {
                        val n = stream.read(bytes, 0, minOf(available, bytes.size))
                        if (n > 0) { read = true; output(decoder.append(bytes, n)) }
                    }
                }
                if (!read && channel.isClosed) break
                if (!read) delay(50)
            }
        } finally { operation.cancel() }
    }
}
