package com.progboy.hermeslink

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Only launches a browser: provider cookies/OAuth stay in that browser. */
class AgentActivity : SecureActivity() {
    private var launched = false
    private var launchJob: Job? = null
    private var generation = 0L
    override fun onVaultLocked() {
        generation++
        launchJob?.cancel(); launchJob = null
        launched = false
    }
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState) }
    override fun onVaultReady() {
        if (launched) return
        launched = true
        val token = ++generation
        val epoch = VaultSession.epoch()
        fun current() = token == generation && epoch == VaultSession.epoch()
        launchJob = lifecycleScope.launch {
            try {
                val profile = withContext(Dispatchers.IO) {
                    SettingsStore.get(this@AgentActivity, intent.getStringExtra("profile_id"))
                }
                if (!current()) return@launch
                if (profile == null || ProfilePolicy.webError(profile.webUrl) != null) {
                    Toast.makeText(this@AgentActivity, "Save a valid HTTPS dashboard URL first.", Toast.LENGTH_LONG).show()
                } else {
                    if (!current()) return@launch
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(profile.webUrl)).addCategory(Intent.CATEGORY_BROWSABLE))
                }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e
            } catch (_: ActivityNotFoundException) {
                if (current()) Toast.makeText(this@AgentActivity, "Install a browser to open your dashboard.", Toast.LENGTH_LONG).show()
            } catch (_: Exception) {
                if (current()) Toast.makeText(this@AgentActivity, "Could not open saved dashboard.", Toast.LENGTH_LONG).show()
            } finally {
                if (token == generation) {
                    launchJob = null
                    if (current()) finish() else launched = false
                }
            }
        }
    }
}
