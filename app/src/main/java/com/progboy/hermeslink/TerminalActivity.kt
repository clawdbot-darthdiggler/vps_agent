package com.progboy.hermeslink

import android.os.Bundle
import android.text.InputType
import android.view.WindowManager
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.jcraft.jsch.*
import kotlinx.coroutines.*
import java.io.OutputStream
import kotlin.concurrent.thread

/** Bounded, line-oriented SSH shell; deliberately not a terminal emulator. */
class TerminalActivity : AppCompatActivity() {
    private lateinit var outputView: TextView
    private lateinit var input: EditText
    private lateinit var send: Button
    private val output = BoundedOutput()
    private val lock = Any()
    private var generation = 0
    private var session: Session? = null
    private var channel: Channel? = null
    private var writer: OutputStream? = null
    private var connection: Job? = null
    private var writeJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(16, 16, 16, 16) }
        setContentView(root)
        applySafeInsets(window, root)
        root.addView(TextView(this).apply { text = "Line-oriented shell only — no full-screen TUI, cursor emulation or interactive password prompts. Sessions disconnect when this screen is left. Commands execute on your server." })
        fun button(title: String, action: () -> Unit) = Button(this).also { it.text = title; it.setOnClickListener { action() }; root.addView(it) }
        button("Connect VPS shell") { connect(false) }
        button("Launch configured Docker /bin/sh") { connect(true) }
        button("Disconnect") { disconnect(); append("\n[Disconnected]\n") }
        outputView = TextView(this).apply { typeface = android.graphics.Typeface.MONOSPACE; setTextIsSelectable(true) }
        root.addView(ScrollView(this).apply { addView(outputView) }, LinearLayout.LayoutParams(-1, 0, 1f))
        input = EditText(this).apply {
            hint = "One shell command line"; isSingleLine = true; isSaveEnabled = false
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            filters = arrayOf(android.text.InputFilter.LengthFilter(4096))
        }
        root.addView(input)
        send = button("Send line") { sendLine() }.apply { isEnabled = false }
        append("No connection started. Choose an explicit shell action above.\n")
    }

    private fun append(text: String) {
        // Strip non-printing control characters; do not claim ANSI emulation.
        outputView.text = output.append(text.filter { it == '\n' || it == '\t' || !it.isISOControl() })
    }

    private fun connect(docker: Boolean) {
        disconnect()
        val token = synchronized(lock) { generation }
        connection = lifecycleScope.launch {
            try {
                val p = withContext(Dispatchers.IO) { SettingsStore.get(this@TerminalActivity, intent.getStringExtra("profile_id")) }
                    ?: error("Profile missing")
                val validation = ProfilePolicy.sshError(p)
                if (validation != null) { append("[$validation]\n"); return@launch }
                val command = if (docker) ProfilePolicy.dockerCommand(p.container) else null
                if (docker && command == null) { append("[Save a valid explicit Docker container name first.]\n"); return@launch }
                append("[Connecting to ${p.sshHost}:${p.sshPort}; checking pinned key…]\n")
                withContext(Dispatchers.IO) {
                    val ssh = JSch().apply { setHostKeyRepository(PinnedHostKeys(p.sshPin)) }
                        .getSession(p.sshUser, p.sshHost, p.sshPort)
                    var ch: Channel? = null
                    try {
                        synchronized(lock) {
                            if (generation != token) throw CancellationException()
                            session = ssh
                        }
                        ssh.setConfig("StrictHostKeyChecking", "yes")
                        ssh.setConfig("PreferredAuthentications", "password")
                        ssh.setPassword(p.sshPassword)
                        ssh.setServerAliveInterval(15000)
                        ssh.setServerAliveCountMax(2)
                        ssh.connect(10000)
                        ensureActive()
                        ch = if (docker) (ssh.openChannel("exec") as ChannelExec).apply {
                            setCommand(command!!); setPty(false)
                            // Merge bounded stderr into the same input stream via JSch's extended stream below.
                        } else (ssh.openChannel("shell") as ChannelShell).apply { setPty(false) }
                        val activeChannel = ch
                        val stream = activeChannel.inputStream
                        val errors = activeChannel.extInputStream
                        val sink = activeChannel.outputStream
                        synchronized(lock) {
                            if (generation != token) throw CancellationException()
                            channel = activeChannel
                        }
                        activeChannel.connect(10000)
                        synchronized(lock) {
                            if (generation != token) throw CancellationException()
                            writer = sink
                        }
                        withContext(Dispatchers.Main) { send.isEnabled = true; append("[Connected: ${if (docker) "Docker shell" else "VPS shell"}]\n") }
                        // Poll both streams; avoids unbounded readLine and stderr pipe deadlocks.
                        val bytes = ByteArray(2048)
                        val sources = listOf(stream to StreamTextDecoder(), errors to StreamTextDecoder())
                        while (isActive) {
                            var readAny = false
                            for ((source, decoder) in sources) {
                                val available = source.available()
                                if (available > 0) {
                                    val count = source.read(bytes, 0, minOf(bytes.size, available))
                                    if (count > 0) {
                                        readAny = true
                                        val text = decoder.append(bytes, count)
                                        withContext(Dispatchers.Main) { if (generation == token) append(text) }
                                    }
                                }
                            }
                            if (!readAny && activeChannel.isClosed) break
                            if (!readAny) delay(50)
                        }
                    } finally {
                        ch?.disconnect(); ssh.disconnect()
                        synchronized(lock) {
                            if (generation == token) { channel = null; session = null; writer = null }
                        }
                    }
                }
                if (generation == token) append("\n[Remote shell closed]\n")
            } catch (e: CancellationException) { throw e
            } catch (_: Exception) {
                if (generation == token) append("\n[Connection failed. Check Tailscale/network, credentials and verified host key pin. Keys are never accepted automatically.]\n")
            } finally { if (generation == token) send.isEnabled = false }
        }
    }

    private fun sendLine() {
        if (writeJob?.isActive == true) return
        val line = input.text.toString()
        if (line.any { it == '\n' || it == '\r' || it == '\u0000' }) { append("[Only one command line is accepted.]\n"); return }
        val (sink, token) = synchronized(lock) { writer?.let { it to generation } } ?: return
        input.setText("")
        // Do not echo commands: users may type secrets.
        writeJob = lifecycleScope.launch {
            try { withContext(Dispatchers.IO) { sink.write((line + "\n").toByteArray(Charsets.UTF_8)); sink.flush() } }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) {
                // A cancelled blocking write may still throw after a replacement connects.
                synchronized(lock) {
                    if (generation == token) {
                        append("[Send failed; reconnect before retrying.]\n")
                        disconnect()
                    }
                }
            }
        }
    }
    private fun disconnect() {
        val resources = synchronized(lock) {
            generation++
            val old = channel to session
            channel = null; session = null; writer = null
            old
        }
        connection?.cancel(); writeJob?.cancel()
        if (::send.isInitialized) send.isEnabled = false
        // Disconnect blocking I/O even after lifecycleScope is cancelled.
        thread(name = "ssh-disconnect", isDaemon = true) {
            try { resources.first?.disconnect() } finally { resources.second?.disconnect() }
        }
    }
    override fun onStop() { disconnect(); super.onStop() }
    override fun onDestroy() { disconnect(); super.onDestroy() }
}
