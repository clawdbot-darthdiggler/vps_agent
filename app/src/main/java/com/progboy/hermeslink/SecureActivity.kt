package com.progboy.hermeslink

import android.app.KeyguardManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowManager
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.*

/** Opt-in local UI access gate, NOT per-use hardware cryptographic authentication.
 * The encrypted store remains encrypted even when this gate is off. */
abstract class SecureActivity : AppCompatActivity() {
    companion object {
        private val handler = Handler(Looper.getMainLooper())
        private var visible = 0
        private var externalFlows = 0
        private val backgroundLock = Runnable { if (visible == 0 && externalFlows == 0) VaultSession.lock() }
    }
    protected var systemFlow = false
        set(value) { if (field != value) { externalFlows += if (value) 1 else -1; field = value } }
    private var cover: LinearLayout? = null
    private var prompt: BiometricPrompt? = null
    private var checking = false
    private var accessCheck: Job? = null
    private var accessGeneration = 0L
    private val lockListener: () -> Unit = { runOnUiThread {
        accessGeneration++
        accessCheck?.cancel(); accessCheck = null; checking = false
        if (!isDestroyed && !isFinishing) showLocked()
    } }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        VaultSession.observe(lockListener)
    }
    override fun onStart() {
        super.onStart(); visible++; handler.removeCallbacks(backgroundLock)
        checkAccess()
    }
    protected fun checkAccess() {
        if (checking) return
        checking = true
        val token = ++accessGeneration
        val epoch = VaultSession.epoch()
        accessCheck = lifecycleScope.launch {
            val allowed = try {
                withContext(Dispatchers.IO) { !SettingsStore.gateEnabled(this@SecureActivity) || VaultSession.unlocked }
            } catch (e: CancellationException) { throw e
            } catch (_: Exception) { false }
            if (token != accessGeneration) return@launch
            checking = false
            accessCheck = null
            if (allowed && epoch == VaultSession.epoch()) { cover?.visibility = View.GONE; onVaultReady() } else showLocked()
        }
    }
    private fun showLocked() {
        onVaultLocked()
        if (cover == null) {
            cover = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; setPadding(24, 80, 24, 24); setBackgroundColor(0xFF202124.toInt())
                isClickable = true; isFocusable = true
                addView(TextView(this@SecureActivity).apply { text = "Vault locked. Credentials have not been reset." })
                addView(Button(this@SecureActivity).apply { text = "Unlock with device"; contentDescription = "unlock_vault"; setOnClickListener { authenticate { checkAccess() } } })
            }
            addContentView(cover, android.view.ViewGroup.LayoutParams(-1, -1))
        }
        cover?.visibility = View.VISIBLE
    }
    protected fun authenticate(success: () -> Unit) {
        if (systemFlow) return
        if (!(getSystemService(KEYGUARD_SERVICE) as KeyguardManager).isDeviceSecure) {
            Toast.makeText(this, "Set a secure device PIN, pattern or password first.", Toast.LENGTH_LONG).show(); return
        }
        systemFlow = true
        prompt = BiometricPrompt(this, ContextCompat.getMainExecutor(this), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                systemFlow = false; VaultSession.unlock(); success()
            }
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                systemFlow = false; VaultSession.lock()
                // No retry loops and no preference changes on cancellation or failure.
                Toast.makeText(this@SecureActivity, "Authentication cancelled or unavailable. Vault stays locked.", Toast.LENGTH_LONG).show()
            }
            override fun onAuthenticationFailed() { VaultSession.lock() }
        })
        try {
            prompt!!.authenticate(BiometricPrompt.PromptInfo.Builder().setTitle("Unlock VPS Agent")
                .setSubtitle("Local app access only; not per-use cryptographic authorization")
                .setAllowedAuthenticators(BIOMETRIC_WEAK or DEVICE_CREDENTIAL).build())
        } catch (_: Exception) { systemFlow = false; VaultSession.lock() }
    }
    protected abstract fun onVaultReady()
    protected open fun onVaultLocked() { }
    override fun onStop() {
        visible--; handler.postDelayed(backgroundLock, 350); super.onStop()
    }
    override fun onDestroy() {
        VaultSession.unobserve(lockListener)
        if (systemFlow) { systemFlow = false; prompt?.cancelAuthentication() }
        super.onDestroy()
    }
}
