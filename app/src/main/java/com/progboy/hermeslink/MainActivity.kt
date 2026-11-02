package com.progboy.hermeslink

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import android.widget.RadioGroup
import com.google.android.material.textfield.TextInputEditText

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val hostInput = findViewById<TextInputEditText>(R.id.hostInput)
        val portInput = findViewById<TextInputEditText>(R.id.portInput)
        val passwordInput = findViewById<TextInputEditText>(R.id.passwordInput)
        val connectButton = findViewById<MaterialButton>(R.id.connectButton)
        val modeGroup = findViewById<RadioGroup>(R.id.modeGroup)

        SettingsStore.load(this)?.let { cfg ->
            hostInput.setText(cfg.host)
            if (cfg.port > 0) portInput.setText(cfg.port.toString())
            passwordInput.setText(cfg.password)
            if (cfg.mode == "terminal") modeGroup.check(R.id.modeTerminal)
        }

        connectButton.setOnClickListener {
            val host = hostInput.text?.toString()?.trim().orEmpty()
            val port = portInput.text?.toString()?.trim()?.toIntOrNull() ?: 0
            val password = passwordInput.text?.toString().orEmpty()

            if (host.isEmpty()) {
                Toast.makeText(this, "Enter a host", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (port <= 0 || port > 65535) {
                Toast.makeText(this, "Enter a valid port", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val mode = if (modeGroup.checkedRadioButtonId == R.id.modeTerminal) "terminal" else "web"
            SettingsStore.save(this, host, port, password, mode)
            val target = if (mode == "terminal") TerminalActivity::class.java else AgentActivity::class.java
            startActivity(Intent(this, target))
        }
    }
}
