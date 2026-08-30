package com.familyporn

import android.annotation.SuppressLint
import android.app.Dialog
import android.graphics.Color
import android.net.http.SslError
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.lagradost.cloudstream3.CommonActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

object CloudflareBypass {
    val cfPhrases = listOf(
        "just a moment", "checking your browser", "ddos-guard",
        "attention required", "verify you are human", "cloudflare",
        "cf-challenge", "cf-browser-verification", "turnstile",
        "challenge", "please wait", "_cf_chl_opt",
        "javascript challenge", "security check", "browser check",
        "one more step", "enable javascript"
    )

    // Safely suspends the background coroutine and opens a full-screen Dialog on the Main UI thread
    suspend fun resolve(url: String): Boolean = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { cont ->
            val activity = CommonActivity.activity
            if (activity == null || activity.isFinishing || activity.isDestroyed) {
                cont.resume(false)
                return@suspendCancellableCoroutine
            }

            var isResolved = false
            var resumed = false

            fun safeResume(success: Boolean) {
                if (!resumed) {
                    resumed = true
                    cont.resume(success)
                }
            }

            val dialog = Dialog(activity, android.R.style.Theme_Black_NoTitleBar_Fullscreen)

            val layout = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(Color.parseColor("#1A1A1A"))
            }

            val header = TextView(activity).apply {
                text = "Solving Cloudflare Anti-Bot... Please Wait"
                setTextColor(Color.WHITE)
                textSize = 16f
                setPadding(32, 32, 32, 32)
            }
            layout.addView(header)

            val progressBar = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 10)
            }
            layout.addView(progressBar)

            val webView = WebView(activity).apply {
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                @SuppressLint("SetJavaScriptEnabled")
                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    databaseEnabled = true
                    useWideViewPort = true
                    loadWithOverviewMode = true
                    cacheMode = WebSettings.LOAD_DEFAULT
                    mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                }

                CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

                // Save exact User-Agent for the Interceptor to use
                if (CFState.userAgent.isBlank()) {
                    CFState.userAgent = settings.userAgentString
                } else {
                    settings.userAgentString = CFState.userAgent
                }

                fun checkSuccess(view: WebView?) {
                    if (isResolved) return
                    val currentUrl = view?.url ?: return
                    val title = view.title?.lowercase() ?: ""
                    val cookies = CookieManager.getInstance().getCookie(currentUrl) ?: ""

                    val isChallenge = cfPhrases.any { title.contains(it) }

                    if (!isChallenge && cookies.contains("cf_clearance")) {
                        isResolved = true
                        CookieManager.getInstance().flush() // Sync to disk

                        header.text = "Success! Resuming..."
                        header.setTextColor(Color.GREEN)

                        Handler(Looper.getMainLooper()).postDelayed({
                            try { dialog.dismiss() } catch (e: Exception) {}
                        }, 1000)
                    }
                }

                webChromeClient = object : WebChromeClient() {
                    override fun onProgressChanged(view: WebView?, newProgress: Int) {
                        progressBar.progress = newProgress
                        progressBar.visibility = if (newProgress == 100) View.GONE else View.VISIBLE
                        if (newProgress == 100) checkSuccess(view)
                    }
                }

                webViewClient = object : WebViewClient() {
                    @SuppressLint("WebViewClientOnReceivedSslError")
                    override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) {
                        handler?.proceed()
                    }
                    override fun onPageFinished(view: WebView?, url: String?) {
                        checkSuccess(view)
                    }
                }
            }

            layout.addView(webView)
            dialog.setContentView(layout)

            dialog.setOnDismissListener {
                safeResume(isResolved)
            }

            dialog.show()
            webView.loadUrl(url)

            cont.invokeOnCancellation {
                runCatching { dialog.dismiss() }
            }
        }
    }
}
