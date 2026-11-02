package com.progboy.hermeslink

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.KeyEvent
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.HttpAuthHandler
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity

/**
 * Browser form factor: renders the Hermes agent web UI in a full-screen WebView.
 */
class AgentActivity : AppCompatActivity() {

    private lateinit var webView: WebView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_agent)

        val cfg = SettingsStore.load(this) ?: run { finish(); return }

        val url = "http://" + cfg.host + ":" + cfg.port
        webView = findViewById(R.id.webview)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                // Keep navigation inside the agent origin; block external redirects.
                return request.url.host != cfg.host
            }

            override fun onReceivedHttpAuthRequest(
                view: WebView,
                handler: HttpAuthHandler,
                host: String,
                realm: String
            ) {
                // Answer HTTP auth challenges with the stored password.
                if (cfg.password.isNotEmpty()) {
                    handler.proceed("hermes", cfg.password)
                } else {
                    handler.cancel()
                }
            }
        }

        val headers = mutableMapOf<String, String>()
        if (cfg.password.isNotEmpty()) {
            headers["Authorization"] = "Basic " + android.util.Base64.encodeToString(
                (":" + cfg.password).toByteArray(), android.util.Base64.NO_WRAP
            )
        }

        webView.loadUrl(url, headers)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK && ::webView.isInitialized && webView.canGoBack()) {
            webView.goBack()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onDestroy() {
        if (::webView.isInitialized) webView.destroy()
        super.onDestroy()
    }
}
