package com.progboy.hermeslink

import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.WindowManager
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {
    private lateinit var form: LinearLayout
    private lateinit var status: TextView
    private lateinit var picker: Spinner
    private lateinit var provider: Spinner
    private val fields = linkedMapOf<String, EditText>()
    private var profiles = emptyList<AgentProfile>()
    private var current = AgentProfile()
    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        form = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 24, 24, 24) }
        val scroll = ScrollView(this).apply { addView(form) }
        setContentView(scroll)
        applySafeInsets(window, scroll)
        label("Agent profiles")
        status = label("Loading encrypted settings…")
        picker = Spinner(this); form.addView(picker)
        button("Load selected profile (discard edits)") {
            profiles.getOrNull(picker.selectedItemPosition)?.let { p ->
                task { withContext(Dispatchers.IO) { SettingsStore.select(this@MainActivity, p.id) }; show(p) }
            }
        }
        button("New profile (discard edits)") { if (!busy) show(AgentProfile()) }
        field("name", "Profile name")
        provider = Spinner(this).apply { adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, listOf("Hermes", "OpenClaw")) }
        form.addView(provider)
        label("VPS SSH — separate machine credentials; Tailscale must already be connected on Android.")
        field("host", "VPS hostname / Tailscale IP")
        field("port", "SSH port", number = true)
        field("user", "SSH username")
        field("password", "SSH password", secret = true)
        field("pin", "Verified SHA256 SSH host-key fingerprint")
        label("Obtain the server's SHA256 host-key fingerprint through a trusted separate channel. No unverified first connections are accepted.")
        field("container", "Optional Docker container name")
        label("Dashboard — provider login happens in your browser, not through SSH or an invented login API.")
        field("url", "HTTPS dashboard URL")
        field("basicUser", "Optional HTTP Basic username (reverse proxy only)")
        field("basicPassword", "Optional HTTP Basic password", secret = true)
        label("Basic credentials are retained separately, but never injected into browser URLs or headers. Enter them manually in the browser if your reverse proxy asks. Use distinct origins for isolated browser sessions; profiles sharing an origin share browser cookies.")
        button("Save profile") { saveThen(null) }
        button("Save & open dashboard in browser") { saveThen(AgentActivity::class.java) }
        button("Save & open SSH controls") { saveThen(TerminalActivity::class.java) }
        button("Delete this saved profile") {
            if (!busy) AlertDialog.Builder(this).setTitle("Delete ${current.name}?")
                .setMessage("Removes this profile and its stored credentials. Browser sessions are not cleared.")
                .setNegativeButton("Cancel", null).setPositiveButton("Delete") { _, _ -> task {
                    withContext(Dispatchers.IO) { SettingsStore.delete(this@MainActivity, current.id) }
                    reload()
                } }.show()
        }
        button("Forget archived legacy shared password") { task {
            withContext(Dispatchers.IO) { SettingsStore.forgetLegacySecret(this@MainActivity) }
            status.text = "Archived legacy secret removed."
        } }
        task { reload() }
    }
    private fun label(text: String) = TextView(this).also { it.text = text; it.setPadding(0, 12, 0, 8); form.addView(it) }
    private fun field(key: String, hint: String, secret: Boolean = false, number: Boolean = false) {
        label(hint)
        fields[key] = EditText(this).also {
            it.hint = hint; it.isSingleLine = true; it.isSaveEnabled = false
            it.inputType = when { secret -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                number -> InputType.TYPE_CLASS_NUMBER
                else -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS }
            form.addView(it)
        }
    }
    private fun button(text: String, action: () -> Unit) { form.addView(Button(this).apply { this.text = text; setOnClickListener { if (!busy) action() } }) }
    private fun show(p: AgentProfile) {
        current = p
        val values = listOf(p.name, p.sshHost, p.sshPort.toString(), p.sshUser, p.sshPassword, p.sshPin, p.container, p.webUrl, p.basicUser, p.basicPassword)
        fields.keys.zip(values).forEach { (key, value) -> fields.getValue(key).setText(value) }
        provider.setSelection(if (p.provider == "OpenClaw") 1 else 0)
        status.text = if (p.name.startsWith("Imported")) "Imported legacy settings: verify HTTPS URL and enter separate SSH credentials and verified pin. Legacy shared password was NOT assigned to SSH." else "Editing ${p.name}. Save before switching; unsaved edits are discarded."
    }
    private suspend fun reload() {
        val result = withContext(Dispatchers.IO) { SettingsStore.loadAll(this@MainActivity) to SettingsStore.selectedId(this@MainActivity) }
        profiles = result.first
        picker.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, profiles.map { "${it.name} (${it.provider})" })
        val index = profiles.indexOfFirst { it.id == result.second }.coerceAtLeast(0)
        picker.setSelection(index)
        show(profiles.getOrNull(index) ?: AgentProfile())
    }
    private fun saveThen(target: Class<*>?) {
        fun value(key: String) = fields.getValue(key).text.toString()
        val p = current.copy(name = value("name").trim(), provider = provider.selectedItem.toString(),
            sshHost = value("host").trim(), sshPort = value("port").toIntOrNull() ?: 0,
            sshUser = value("user").trim(), sshPassword = value("password"), sshPin = value("pin").trim(),
            container = value("container").trim(), webUrl = value("url").trim(),
            basicUser = value("basicUser"), basicPassword = value("basicPassword"))
        val error = when {
            p.name.isBlank() -> "Enter a profile name."
            target == AgentActivity::class.java -> ProfilePolicy.webError(p.webUrl)
            target == TerminalActivity::class.java -> ProfilePolicy.sshError(p)
            else -> null
        }
        if (error != null) { status.text = error; return }
        task {
            withContext(Dispatchers.IO) { SettingsStore.save(this@MainActivity, p) }
            reload(); status.text = "Saved ${p.name}."
            if (target != null) startActivity(Intent(this@MainActivity, target).putExtra("profile_id", p.id))
        }
    }
    private fun task(block: suspend () -> Unit) {
        if (busy) return
        busy = true
        setEditorsEnabled(false)
        lifecycleScope.launch {
            try { block() }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Exception) { status.text = "Encrypted settings unavailable or could not be saved. No credentials were reset. Retry after unlocking the device." }
            finally { busy = false; setEditorsEnabled(true) }
        }
    }
    private fun setEditorsEnabled(enabled: Boolean) {
        fields.values.forEach { it.isEnabled = enabled }
        provider.isEnabled = enabled
        picker.isEnabled = enabled
    }
}
