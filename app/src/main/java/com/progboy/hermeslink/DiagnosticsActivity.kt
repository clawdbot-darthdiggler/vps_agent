package com.progboy.hermeslink

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.*
import kotlin.concurrent.thread

class DiagnosticsActivity : SecureActivity() {
    private lateinit var status: TextView
    private var job: Job? = null
    private var operation: ConnectionOperation? = null
    private var report = ""
    private var generation = 0
    private var available = false
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(20, 10, 20, 10) }
        setContentView(root); applySafeInsets(window, root)
        root.addView(TextView(this).apply { text = "Saved connection diagnostics\nChecks do not change server settings. A reachable host is not necessarily a ready agent. SSH phases have 4-second timeouts and the complete check has a 25-second deadline. Dashboard uses HTTPS HEAD without credentials or redirects." })
        fun button(text: String, action: () -> Unit) { root.addView(Button(this).apply { this.text = text; isSaveEnabled = false; setOnClickListener { if (available) action() } }) }
        button("Run saved connection checks") { runChecks() }
        button("Cancel checks") { cancel(); status.text = DiagnosticCode.CANCELLED.action }
        button("Preview diagnostic report / export") { preview() }
        status = TextView(this).apply { isSaveEnabled = false; text = "No checks run." }
        root.addView(ScrollView(this).apply { addView(status) })
    }
    override fun onVaultReady() { available = true }
    override fun onVaultLocked() { available = false; cancel(); report = ""; if (::status.isInitialized) status.text = "Locked; checks closed." }
    private fun runChecks() {
        cancel(); val token = generation
        val op = ConnectionOperation(); operation = op
        status.text = "Checking…"; report = ""
        job = lifecycleScope.launch {
            try {
                val results = withContext(Dispatchers.IO) {
                    val s = SettingsStore.loadState(this@DiagnosticsActivity)
                    val profile = intent.getStringExtra("profile_id")?.let { s.resolve(it) } ?: intent.getStringExtra("host_id")?.let { id ->
                        val h = s.hosts.single { it.id == id }
                        HostAgentState(listOf(h), listOf(AgentRecord(id = "diagnostic", hostId = id))).resolve("diagnostic")
                    } ?: error("Missing saved record")
                    ConnectionService().diagnose(profile, op)
                }
                if (token == generation) {
                    report = DiagnosticReport.preview(results)
                    status.text = results.joinToString("\n\n") { "${it.stage}: ${it.code} (${it.elapsedMs} ms)\n${it.action}" }
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { if (token == generation) status.text = "Encrypted settings unavailable or record missing. No settings were reset." }
        }
    }
    private fun preview() {
        if (report.isEmpty()) { status.text = "Run checks before exporting."; return }
        val dialog = AlertDialog.Builder(this).setTitle("Exact export preview")
            .setMessage("Only report format version, stage, code and elapsed time are included. No endpoints, usernames, keys, tokens, exceptions or terminal output.\n\n$report")
            .setNegativeButton("Close", null).setPositiveButton("Export text…") { _, _ ->
                systemFlow = true
                try { startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                    .setType("text/plain").putExtra(Intent.EXTRA_TITLE, "connection-diagnostics.txt"), 402) }
                catch (_: Exception) { systemFlow = false; status.text = "Document picker unavailable." }
            }.create()
        dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE); dialog.show()
    }
    @Deprecated("Platform result callback")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 402) return
        systemFlow = false
        val uri = data?.data ?: return
        if (resultCode != RESULT_OK || report.isEmpty()) return
        val snapshot = report
        lifecycleScope.launch {
            try { withContext(Dispatchers.IO) { contentResolver.openOutputStream(uri, "wt")?.use { it.write(snapshot.toByteArray(Charsets.UTF_8)) } ?: error("No stream") }; status.text = "Allowlisted diagnostic report exported." }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { status.text = "Report could not be exported." }
        }
    }
    private fun cancel() {
        generation++; job?.cancel(); job = null
        val old = operation; operation = null
        thread(isDaemon = true, name = "diagnostics-cancel") { old?.cancel() }
    }
    override fun onStop() { cancel(); super.onStop() }
    override fun onDestroy() { cancel(); super.onDestroy() }
}
