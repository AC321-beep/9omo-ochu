package com.familyporn

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.lagradost.api.Log

class CloudflareWebViewDialog(
    private val context: Context,
    private val targetUrl: String,
    private val onFinished: ((Boolean) -> Unit)? = null
) {
    private val TAG = "FamilyPorn_CFDialog"
    private val handler = Handler(Looper.getMainLooper())
    private var webView: WebView? = null
    private var dialog: Dialog? = null
    private var statusText: TextView? = null
    private var progressBar: ProgressBar? = null
    private var cookiesSaved = false
    private var pollElapsedMs = 0L
    private val pollIntervalMs = 2000L
    private val pollTimeoutMs = 120000L

    private val targetHost: String by lazy {
        runCatching {
            val uri = android.net.Uri.parse(targetUrl)
            "${uri.scheme}://${uri.host}"
        }.getOrElse { targetUrl }
    }

    private val cookiePollRunnable = object : Runnable {
        override fun run() {
            if (cookiesSaved) return
            CookieManager.getInstance().flush()
            val cookieStr = CookieManager.getInstance().getCookie(targetHost) ?: ""
            Log.d(TAG, "Poll [$pollElapsedMs ms] cookies: $cookieStr")
            when {
                cookieStr.contains("cf_clearance") -> saveCookiesAndDismiss(cookieStr)
                pollElapsedMs >= pollTimeoutMs -> {
                    updateStatus("⏱️ Timed out. Try solving the CAPTCHA then tap Bypass again.")
                }
                else -> scheduleNextPoll()
            }
        }
    }

    private fun scheduleNextPoll() {
        pollElapsedMs += pollIntervalMs
        updateStatus("⏳ Waiting for cookies… (${pollElapsedMs / 1000}s)")
        handler.postDelayed(cookiePollRunnable, pollIntervalMs)
    }

    @SuppressLint("SetJavaScriptEnabled")
    fun show() {
        dialog = Dialog(context, android.R.style.Theme_Black_NoTitleBar_Fullscreen).apply {
            setContentView(createView())
            setCanceledOnTouchOutside(false)
            setOnDismissListener {
                if (!cookiesSaved) {
                    handler.removeCallbacks(cookiePollRunnable)
                    onFinished?.invoke(false)
                }
            }
        }
        dialog?.show()

        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
            flush()
        }
        webView?.loadUrl(targetUrl)
        handler.postDelayed(cookiePollRunnable, pollIntervalMs)
    }

    private fun createView(): ViewGroup {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 24, 32, 24)
            setBackgroundColor(Color.parseColor("#1A1A2E"))
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        root.addView(TextView(context).apply {
            text = "🛡️ FamilyPorn – Cloudflare Bypass"
            textSize = 18f
            setTextColor(Color.WHITE)
            setPadding(0, 0, 0, 8)
        })

        statusText = TextView(context).apply {
            text = "Loading challenge page…"
            textSize = 13f
            setTextColor(Color.parseColor("#A0A0B0"))
            setPadding(0, 0, 0, 4)
        }
        root.addView(statusText)

        root.addView(TextView(context).apply {
            text = "Solve any CAPTCHA shown below. The dialog will close automatically once done."
            textSize = 11f
            setTextColor(Color.parseColor("#707080"))
            setPadding(0, 0, 0, 12)
        })

        progressBar = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.bottomMargin = 12 }
        }
        root.addView(progressBar)

        val webViewHeight = (context.resources.displayMetrics.heightPixels * 0.70).toInt()
        val wvContainer = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                webViewHeight
            )
        }
        webView = buildWebView()
        wvContainer.addView(webView)
        root.addView(wvContainer)

        return root
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun buildWebView(): WebView {
        val wv = WebView(context)
        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            @Suppress("DEPRECATION")
            mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            allowContentAccess = true
            allowFileAccess = true
            loadsImagesAutomatically = true
        }

        wv.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                super.onProgressChanged(view, newProgress)
                if (!cookiesSaved) updateStatus("Loading… $newProgress%")
            }
        }

        wv.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                if (cookiesSaved) return
                val title = view?.title ?: ""
                if (isChallengeTitle(title)) {
                    updateStatus("🔄 Challenge active – solve the CAPTCHA above")
                } else {
                    updateStatus("✏️ Page loaded – checking cookies…")
                    CookieManager.getInstance().flush()
                    val cookiesFromTarget = CookieManager.getInstance().getCookie(targetHost) ?: ""
                    val cookiesFromUrl = url?.let {
                        runCatching {
                            val uri = android.net.Uri.parse(it)
                            CookieManager.getInstance().getCookie("${uri.scheme}://${uri.host}")
                        }.getOrNull()
                    } ?: ""
                    val bestCookies = when {
                        cookiesFromTarget.contains("cf_clearance") -> cookiesFromTarget
                        cookiesFromUrl.contains("cf_clearance")    -> cookiesFromUrl
                        else                                        -> null
                    }
                    if (bestCookies != null) {
                        handler.removeCallbacks(cookiePollRunnable)
                        saveCookiesAndDismiss(bestCookies)
                    }
                }
            }
        }
        return wv
    }

    private fun isChallengeTitle(title: String): Boolean {
        val challengeTitles = listOf(
            "just a moment", "just a moment...",
            "checking your browser", "attention required",
            "ddos-guard", "one more step"
        )
        return challengeTitles.any { title.lowercase().contains(it) }
    }

    private fun saveCookiesAndDismiss(cookieStr: String) {
        if (cookiesSaved) return
        cookiesSaved = true
        handler.removeCallbacks(cookiePollRunnable)

        FamilyPornPlugin.cfCookies = cookieStr
        FamilyPornPlugin.cfCookieHost = targetHost
        webView?.settings?.userAgentString?.let { ua ->
            FamilyPornPlugin.cfUserAgent = ua
        }

        updateStatus("✅ Done! Cookies saved.")
        handler.postDelayed({
            dialog?.dismiss()
            onFinished?.invoke(true)
        }, 1500)
    }

    private fun updateStatus(msg: String) {
        statusText?.text = msg
        if (msg.startsWith("✅")) {
            progressBar?.visibility = android.view.View.GONE
            statusText?.setTextColor(Color.parseColor("#4CAF50"))
        } else {
            progressBar?.visibility = android.view.View.VISIBLE
            statusText?.setTextColor(Color.parseColor("#A0A0B0"))
        }
    }

    fun dismiss() {
        dialog?.dismiss()
    }
}
