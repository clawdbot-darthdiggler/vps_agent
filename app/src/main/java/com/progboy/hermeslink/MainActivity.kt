package com.progboy.hermeslink

import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Host and agent editors never carry each other's secrets. Navigation discards unsaved edits. */
class MainActivity : SecureActivity() {
    private lateinit var root: LinearLayout
    private lateinit var body: LinearLayout
    private lateinit var status: TextView
    private var state = HostAgentState()
    private var busy = false
    private var loaded = false
    private var hostsTab = false
    private var importReceiver: ((String) -> Unit)? = null
    private var activeTask: kotlinx.coroutines.Job? = null
    private var pendingConfirmation: AlertDialog? = null
    private var confirmationAction: (() -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), 0, dp(16), 0) }
        setContentView(root); applySafeInsets(window, root)
        label(root, "VPS Agent", 24f)
        val tabs = LinearLayout(this); root.addView(tabs)
        button(tabs, "Hosts", "hosts_tab") { hostsTab = true; showList() }
        button(tabs, "Agents", "agents_tab") { hostsTab = false; showList() }
        status = label(root, "Loading encrypted settings…").apply { contentDescription = "status"; accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
        body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(ScrollView(this).apply { addView(body) }, LinearLayout.LayoutParams(-1, 0, 1f))
        setControlsEnabled(false)
    }
    override fun onVaultReady() { if (!loaded) task { reload() } }
    override fun onVaultLocked() {
        dismissConfirmation()
        activeTask?.cancel(); busy = false; loaded = false; state = HostAgentState(); importReceiver = null
        if (::body.isInitialized) body.removeAllViews()
        if (::status.isInitialized) status.text = "Session locked. Unsaved edits and session-only credentials were cleared."
    }
    private fun check(tag: String, title: String, selected: Boolean) = CheckBox(this).also {
        it.text = title; it.contentDescription = tag; it.isChecked = selected; it.isSaveEnabled = false; body.addView(it)
    }
    private fun vaultPolicy() = task {
        val enabled = withContext(Dispatchers.IO) { SettingsStore.gateEnabled(this@MainActivity) }
        editorHeader("Vault lock policy")
        label(body, "Local device authentication gate: ${if (enabled) "ON" else "OFF (default)"}. Secrets always use encrypted storage. This is a local UI gate, NOT per-use hardware-backed cryptographic authorization. Device credential or available biometric can unlock. Set a secure device screen lock first. Backgrounding or Lock now clears session-only secrets and unsaved editors, and closes connections. The app's own document picker/authentication dialog is exempt while active.")
        button(body, if (enabled) "Disable local device gate" else "Enable local device gate", "toggle_gate") {
            if (enabled) task { withContext(Dispatchers.IO) { SettingsStore.setGateEnabled(this@MainActivity, false) }; reload() }
            else authenticate { task { withContext(Dispatchers.IO) { SettingsStore.setGateEnabled(this@MainActivity, true) }; reload() } }
        }
    }
    @Deprecated("Platform result callback")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 401) return
        systemFlow = false
        val receive = importReceiver; importReceiver = null
        val uri = data?.data
        if (resultCode != RESULT_OK || uri == null || receive == null) return
        task {
            try {
                val key = withContext(Dispatchers.IO) { contentResolver.openInputStream(uri)?.use { PrivateKeyImport.read(it) } ?: error("No stream") }
                receive(key); status.text = "RSA PEM imported into this editor only. Save host to apply."
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Exception) { status.text = "Import rejected: use a readable RSA PKCS#1 PEM private key of at most 64 KiB. No document permission retained." }
        }
    }
    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
    private fun label(parent: LinearLayout, text: String, size: Float = 16f) = TextView(this).also {
        it.text = text; it.textSize = size; it.setPadding(0, dp(8), 0, dp(6)); parent.addView(it)
    }
    private fun button(parent: LinearLayout, text: String, tag: String, action: () -> Unit) = Button(this).also {
        it.text = text; it.contentDescription = tag; it.isSaveEnabled = false
        it.setOnClickListener { if (!busy && loaded) action() }; parent.addView(it)
    }
    private fun field(key: String, title: String, value: String, secret: Boolean = false, number: Boolean = false): EditText {
        label(body, title)
        return EditText(this).also {
            it.contentDescription = key; it.hint = title; it.isSingleLine = true; it.isSaveEnabled = false
            it.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
            it.inputType = when { secret -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                number -> InputType.TYPE_CLASS_NUMBER
                else -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS }
            it.setText(value); body.addView(it)
        }
    }
    private fun spinner(key: String, title: String, values: List<String>, selection: Int): Spinner {
        label(body, title)
        return Spinner(this).also {
            it.contentDescription = key; it.isSaveEnabled = false
            it.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, values)
            it.setSelection(selection.coerceAtLeast(0)); body.addView(it)
        }
    }
    private fun card(tag: String): LinearLayout = LinearLayout(this).also {
        it.orientation = LinearLayout.VERTICAL; it.contentDescription = tag
        it.setPadding(dp(12), dp(8), dp(12), dp(12))
        it.background = android.graphics.drawable.GradientDrawable().apply {
            setColor(0xFF24272B.toInt()); cornerRadius = dp(12).toFloat(); setStroke(dp(1), 0xFF70757A.toInt())
        }
        body.addView(it, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
    }
    private fun showList() {
        body.removeAllViews()
        status.text = "Saved settings only — connection status not checked."
        label(body, if (hostsTab) "Hosts" else "Agents", 22f)
        if (hostsTab) {
            button(body, "Add host", "add_host") { editHost(HostProfile()) }
            if (state.hosts.isEmpty()) label(body, "No hosts yet. Add your VPS once, then attach agents.")
            state.hosts.forEach { h ->
                val card = card("host_card_${h.id}")
                label(card, h.name, 20f); label(card, "${h.sshHost}:${h.sshPort} · ${h.sshUser}\n${state.agents.count { it.hostId == h.id }} linked agents")
                button(card, "Edit host", "edit_host_${h.id}") { editHost(h) }
                button(card, "Test saved host", "test_host_${h.id}") { startActivity(Intent(this, DiagnosticsActivity::class.java).putExtra("host_id", h.id)) }
            }
        } else {
            button(body, "Add agent", "add_agent") {
                if (state.hosts.isEmpty()) { status.text = "Add a host in Hosts before creating an agent." }
                else editAgent(AgentRecord(hostId = ""))
            }
            if (state.agents.isEmpty()) label(body, "No agents yet. Add a host, then configure Hermes or OpenClaw.")
            state.agents.forEach { a ->
                val h = state.hosts.single { it.id == a.hostId }
                val card = card("agent_card_${a.id}")
                label(card, a.name, 20f)
                label(card, "${a.provider} · ${h.name}\nContainer: ${a.container.ifBlank { "Not configured" }}\nPreferred access: ${a.preferredAccess}")
                button(card, "Edit agent", "edit_agent_${a.id}") { editAgent(a) }
                button(card, "Test connection / diagnostics", "test_agent_${a.id}") { startActivity(Intent(this, DiagnosticsActivity::class.java).putExtra("profile_id", a.id)) }
                button(card, "Open dashboard", "open_web_${a.id}") { open(a.id, false) }
                button(card, "Open SSH controls", "open_shell_${a.id}") { open(a.id, true) }
            }
        }
        button(body, "Vault lock policy", "vault_policy") { vaultPolicy() }
        button(body, "Lock now / clear session secrets", "lock_now") { VaultSession.lock(); checkAccess() }
        button(body, "Forget archived legacy passwords", "forget_legacy") { task {
            withContext(Dispatchers.IO) { SettingsStore.forgetLegacySecret(this@MainActivity) }
            status.text = "Archived legacy passwords removed. Active host and agent credentials are unchanged."
        } }
    }
    private fun editorHeader(title: String) {
        body.removeAllViews(); label(body, title, 22f)
        status.text = "Save changes before leaving. Switching lists discards unsaved edits."
        button(body, "Back to list (discard edits)", "back_list") { showList() }
    }
    private fun editHost(h: HostProfile) {
        editorHeader("Host details")
        val name = field("host_name", "Host display name", h.name)
        val address = field("host_address", "VPS hostname / Tailscale IP", h.sshHost)
        val port = field("host_port", "SSH port", h.sshPort.toString(), number = true)
        val user = field("ssh_user", "SSH username", h.sshUser)
        val password = field("ssh_password", "SSH password", h.sshPassword, secret = true)
        val pin = field("ssh_pin", "Verified SHA256 host-key fingerprint", h.sshPin)
        val keyTypes = listOf("Legacy pin — select key type") + FingerprintPolicy.types
        val keyType = spinner("host_key_type", "SSH host key type (RSA uses SHA-2 signatures)", keyTypes, keyTypes.indexOf(h.hostKeyType))
        val verified = check("pin_verified", "I independently verified this key type AND fingerprint using a trusted console/channel", false)
        pin.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { verified.isChecked = false }
            override fun afterTextChanged(s: android.text.Editable?) = Unit
        })
        keyType.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) { verified.isChecked = false }
            override fun onNothingSelected(parent: AdapterView<*>?) { verified.isChecked = false }
        }
        val auth = spinner("auth_method", "SSH authentication", listOf("Password", "RSA PEM private key"), if (h.authMethod == "private_key") 1 else 0)
        val rememberPassword = check("remember_password", "Remember SSH password encrypted", h.rememberPassword)
        var importedKey = h.privateKeyPem
        val keyStatus = label(body, if (importedKey.isBlank()) "No imported private key" else "RSA private key present (not displayed)")
        button(body, "Import RSA PEM private key (64 KiB maximum)", "import_key") {
            importReceiver = { key -> importedKey = key; keyStatus.text = "RSA PEM imported; Save host to apply" }
            systemFlow = true
            try { startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), 401) }
            catch (_: Exception) { systemFlow = false; importReceiver = null; status.text = "No system document picker available." }
        }
        val rememberKey = check("remember_key", "Remember private key encrypted", h.rememberKey)
        val passphrase = field("key_passphrase", "Private-key passphrase (optional)", h.keyPassphrase, secret = true)
        val rememberPassphrase = check("remember_passphrase", "Remember passphrase encrypted (OFF by default)", h.rememberPassphrase)
        label(body, "Supported: RSA PKCS#1 PEM, unencrypted or passphrase encrypted. OpenSSH / PKCS#8 / Ed25519 imports are rejected. Unremembered secrets last only this foreground session. Import does not retain document access.")
        label(body, "Shared by all linked agents. Tailscale must already be connected. Verify the fingerprint through a trusted separate channel; unverified keys are never accepted.")
        button(body, "Save host", "save_host") {
            val updated = h.copy(name = name.text.toString().trim(), sshHost = address.text.toString().trim(),
                sshPort = port.text.toString().toIntOrNull() ?: 0, sshUser = user.text.toString().trim(),
                sshPassword = password.text.toString(), sshPin = pin.text.toString().trim(),
                hostKeyType = keyTypes[keyType.selectedItemPosition].takeIf { it in FingerprintPolicy.types }.orEmpty(),
                pinVerified = h.pinVerified || verified.isChecked, authMethod = if (auth.selectedItemPosition == 1) "private_key" else "password",
                privateKeyPem = importedKey, keyPassphrase = passphrase.text.toString(), rememberPassword = rememberPassword.isChecked,
                rememberKey = rememberKey.isChecked, rememberPassphrase = rememberPassphrase.isChecked)
            val pinChanged = updated.sshPin != h.sshPin || updated.hostKeyType != h.hostKeyType
            if (pinChanged && !FingerprintPolicy.canConfirm(updated.sshPin, updated.hostKeyType, verified.isChecked)) {
                status.text = "Choose a key type and independently verify its SHA256 fingerprint, then check the confirmation. No key was trusted."; return@button
            }
            if (updated.name.isBlank() || updated.sshPort !in 1..65535) { status.text = "Enter a host name and SSH port from 1–65535." }
            else {
                val save = { task { withContext(Dispatchers.IO) { SettingsStore.saveHost(this@MainActivity, updated) }; hostsTab = true; reload() } }
                if (pinChanged && h.sshPin.isNotBlank()) confirm("Replace trusted host key?",
                    "This changes the identity trusted by every linked agent. Only continue after independent verification of the new key type and fingerprint.",
                    "Replace verified key", save)
                else save()
            }
        }
        if (state.hosts.any { it.id == h.id }) button(body, "Forget SSH credentials", "forget_host") { task {
            withContext(Dispatchers.IO) { SettingsStore.forgetHostCredentials(this@MainActivity, h.id) }; reload()
        } }
        if (state.hosts.any { it.id == h.id }) button(body, "Delete host", "delete_host") {
            if (state.agents.any { it.hostId == h.id }) status.text = "Host is used by saved agents. Reassign or delete those agents first, then delete this host."
            else confirm("Delete host?", "Removes this host and its SSH credentials.") {
                task { withContext(Dispatchers.IO) { SettingsStore.deleteHost(this@MainActivity, h.id) }; hostsTab = true; reload() }
            }
        }
    }
    private fun editAgent(a: AgentRecord) {
        editorHeader("Agent details")
        val name = field("agent_name", "Agent display name", a.name)
        val providers = listOf("Hermes", "OpenClaw").let { if (a.provider in it) it else it + a.provider }
        val provider = spinner("agent_provider", "Provider", providers, providers.indexOf(a.provider))
        val hosts = state.hosts.toList()
        val host = spinner("agent_host", "Host — select explicitly", listOf("Select a host…") + hosts.map { "${it.name} (${it.sshHost})" }, hosts.indexOfFirst { it.id == a.hostId } + 1)
        val container = field("agent_container", "Optional Docker container", a.container)
        val url = field("agent_url", "HTTPS dashboard URL", a.webUrl)
        val basicUser = field("basic_user", "Optional HTTP Basic username", a.basicUser)
        val basicPassword = field("basic_password", "Optional HTTP Basic password", a.basicPassword, secret = true)
        val rememberAgent = check("remember_agent", "Remember agent password encrypted", a.rememberCredential)
        if (state.agents.any { it.id == a.id }) button(body, "Forget agent credential only", "forget_agent") { task {
            withContext(Dispatchers.IO) { SettingsStore.forgetAgentCredentials(this@MainActivity, a.id) }; reload()
        } }
        val modes = listOf("Browser", "SSH controls")
        val preferred = spinner("preferred_access", "Preferred access", modes, modes.indexOf(a.preferredAccess))
        label(body, "Web credentials are separate from SSH. They are retained but not injected into browser URLs or headers. Enter them in the browser if asked. Browser cookies belong to the browser and are shared by origin.")
        if (a.name.startsWith("Imported")) label(body, "Imported legacy settings: review the URL and enter separate SSH credentials and a verified pin. The ambiguous legacy password was not assigned to SSH.")
        button(body, "Save agent", "save_agent") {
            val selectedHost = hosts.getOrNull(host.selectedItemPosition - 1)
            if (selectedHost == null) { status.text = "Select a saved host first."; return@button }
            val updated = a.copy(name = name.text.toString().trim(), provider = providers[provider.selectedItemPosition], hostId = selectedHost.id,
                container = container.text.toString().trim(), webUrl = url.text.toString().trim(),
                basicUser = basicUser.text.toString(), basicPassword = basicPassword.text.toString(), preferredAccess = modes[preferred.selectedItemPosition], rememberCredential = rememberAgent.isChecked)
            if (updated.name.isBlank()) { status.text = "Enter an agent name."; return@button }
            task { withContext(Dispatchers.IO) { SettingsStore.saveAgent(this@MainActivity, updated) }; hostsTab = false; reload() }
        }
        if (state.agents.any { it.id == a.id }) button(body, "Delete agent", "delete_agent") {
            confirm("Delete agent?", "Removes this agent and its web credentials. The shared host and browser sessions are not deleted.") {
                task { withContext(Dispatchers.IO) { SettingsStore.delete(this@MainActivity, a.id) }; hostsTab = false; reload() }
            }
        }
    }
    private fun dismissConfirmation() {
        confirmationAction = null
        val dialog = pendingConfirmation; pendingConfirmation = null
        dialog?.dismiss()
    }
    override fun onDestroy() { dismissConfirmation(); super.onDestroy() }
    private fun confirm(title: String, message: String, positive: String = "Delete", action: () -> Unit) {
        dismissConfirmation()
        val epoch = VaultSession.epoch()
        val dialog = AlertDialog.Builder(this).setTitle(title).setMessage(message).setNegativeButton("Cancel", null)
            .setPositiveButton(positive) { source, _ ->
                // Never capture the secret-bearing action in the dialog's queued listener.
                val current = pendingConfirmation === source && epoch == VaultSession.epoch() && loaded && !busy
                val execute = if (current) confirmationAction else null
                dismissConfirmation()
                execute?.invoke()
            }.create()
        confirmationAction = action; pendingConfirmation = dialog
        dialog.setOnDismissListener { if (pendingConfirmation === dialog) dismissConfirmation() }
        dialog.show()
    }
    private fun open(id: String, shell: Boolean) = task {
        val p = withContext(Dispatchers.IO) { SettingsStore.get(this@MainActivity, id) }
        val error = if (p == null) "Agent no longer exists. Reload the list." else if (shell) ProfilePolicy.sshError(p) else ProfilePolicy.webError(p.webUrl)
        if (error != null) status.text = error
        else {
            withContext(Dispatchers.IO) { SettingsStore.select(this@MainActivity, id) }
            startActivity(Intent(this@MainActivity, if (shell) TerminalActivity::class.java else AgentActivity::class.java).putExtra("profile_id", id))
        }
    }
    private suspend fun reload() {
        state = withContext(Dispatchers.IO) { SettingsStore.loadState(this@MainActivity) }
        loaded = true; showList()
    }
    private fun task(block: suspend () -> Unit) {
        if (busy) return
        busy = true; setControlsEnabled(false)
        activeTask = lifecycleScope.launch {
            try { block() }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Exception) {
                status.text = "Encrypted settings unavailable or could not be saved. No credentials were reset. Unlock the device and retry; unsupported or damaged data needs recovery, not a reset."
                if (!loaded) {
                    body.removeAllViews()
                    Button(this@MainActivity).also { retry -> retry.text = "Retry loading"; retry.contentDescription = "retry_load"
                        retry.setOnClickListener { task { reload() } }; body.addView(retry) }
                }
            } finally { busy = false; setControlsEnabled(loaded)
                if (!loaded) (body.getChildAt(0) as? Button)?.isEnabled = true
            }
        }
    }
    private fun setControlsEnabled(enabled: Boolean) {
        fun visit(v: View) {
            if (v is EditText || v is Spinner || v is Button) v.isEnabled = enabled
            if (v is ViewGroup) for (i in 0 until v.childCount) visit(v.getChildAt(i))
        }
        visit(root)
    }
}
