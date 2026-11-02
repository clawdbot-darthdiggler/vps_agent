package com.progboy.hermeslink

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Only launches a browser: provider cookies/OAuth stay in that browser. */
class AgentActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycleScope.launch {
            try {
                val profile = withContext(Dispatchers.IO) {
                    SettingsStore.get(this@AgentActivity, intent.getStringExtra("profile_id"))
                }
                if (profile == null || ProfilePolicy.webError(profile.webUrl) != null) {
                    Toast.makeText(this@AgentActivity, "Save a valid HTTPS dashboard URL first.", Toast.LENGTH_LONG).show()
                } else {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(profile.webUrl)).addCategory(Intent.CATEGORY_BROWSABLE))
                }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e
            } catch (_: ActivityNotFoundException) {
                Toast.makeText(this@AgentActivity, "Install a browser to open your dashboard.", Toast.LENGTH_LONG).show()
            } catch (_: Exception) {
                Toast.makeText(this@AgentActivity, "Could not open saved dashboard.", Toast.LENGTH_LONG).show()
            } finally { finish() }
        }
    }
}
