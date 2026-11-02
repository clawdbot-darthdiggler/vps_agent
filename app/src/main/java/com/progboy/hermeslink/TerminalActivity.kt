package com.progboy.hermeslink

import android.os.Bundle
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.jcraft.jsch.ChannelShell
import com.jcraft.jsch.JSch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Terminal form factor: SSH shell into the VPS running the Hermes docker agent.
 */
class TerminalActivity : AppCompatActivity() {

    private lateinit var outputView: TextView
    private lateinit var inputField: EditText
    private var shellChannel: ChannelShell? = null
    private val output = StringBuilder()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_terminal)

        outputView = findViewById(R.id.terminalOutput)
        inputField = findViewById(R.id.terminalInput)
        val sendButton = findViewById<ImageButton>(R.id.sendButton)

        appendOutput("Connecting to Hermes host...\n")

        lifecycleScope.launch { connectSsh() }

        sendButton.setOnClickListener { sendLine() }
        inputField.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                sendLine()
                true
            } else false
        }
    }

    private fun sendLine() {
        val line = inputField.text?.toString().orEmpty()
        if (line.isEmpty()) return
        inputField.setText("")
        appendOutput("> " + line + "\n")
        val ch = shellChannel ?: return
        try {
            ch.outputStream.write((line + "\n").toByteArray())
            ch.outputStream.flush()
        } catch (e: Exception) {
            appendOutput("[write error: " + e.message + "]\n")
        }
    }

    private fun appendOutput(text: String) {
        runOnUiThread {
            output.append(text)
            outputView.text = output
            (outputView.parent as? ScrollView)?.let { sv ->
                sv.post { sv.fullScroll(ScrollView.FOCUS_DOWN) }
            }
        }
    }

    private suspend fun connectSsh() = withContext(Dispatchers.IO) {
        val cfg = SettingsStore.load(this@TerminalActivity) ?: return@withContext
        try {
            val jsch = JSch()
            val session = jsch.getSession("hermes", cfg.host, 22)
            session.setPassword(cfg.password)
            session.setConfig("StrictHostKeyChecking", "no")
            session.connect(10000)

            val channel = session.openChannel("shell") as ChannelShell
            channel.setPtyType("dumb")
            channel.connect(10000)
            shellChannel = channel

            appendOutput("Connected.\n")

            val reader = java.io.BufferedReader(java.io.InputStreamReader(channel.inputStream))
            while (channel.isConnected) {
                val line = reader.readLine() ?: break
                appendOutput(line + "\n")
            }
            appendOutput("\n[disconnected]\n")
        } catch (e: Exception) {
            appendOutput("[connection failed: " + e.message + "]\n")
        }
    }

    override fun onDestroy() {
        try { shellChannel?.disconnect() } catch (_: Exception) {}
        super.onDestroy()
    }
}
