package com.familyporn

import android.annotation.SuppressLint
import android.app.Dialog
import android.graphics.Color
import android.net.http.SslError
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.lagradost.cloudstream3.CommonActivity

class CFDialog(private val url: String, private val onResult: (Boolean) -> Unit) {
    private var isResolved = false
    private var dialog: Dialog? = null

    @SuppressLint("SetJavaScriptEnabled")
    fun show() {
        val activity = CommonActivity.activity ?: run {
            onResult(false)
            return
        }

        Log.d("CF_DEBUG", "Opening CFDialog for URL: $url")

        dialog = Dialog(activity)
        
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

            if (CFState.userAgent.isBlank()) {
                CFState.userAgent = settings.userAgentString
            } else {
                settings.userAgentString = CFState.userAgent
            }
            Log.d("CF_DEBUG", "WebView User-Agent set to: ${settings.userAgentString}")

            fun checkSuccess(view: WebView?) {
                if (isResolved) return
                val currentUrl = view?.url ?: return
                val title = view.title?.lowercase() ?: ""
                val cookies = CookieManager.getInstance().getCookie(currentUrl) ?: ""

                val isChallenge = listOf("just a moment", "attention required", "security verification", "cloudflare").any { title.contains(it) }

                if (!isChallenge && cookies.contains("cf_clearance")) {
                    Log.d("CF_DEBUG", "Bypass SUCCESS. Cookies found: $cookies")
                    isResolved = true
                    CookieManager.getInstance().flush()
                    header.text = "Success! Resuming..."
                    header.setTextColor(Color.GREEN)
                    Handler(Looper.getMainLooper()).postDelayed({
                        try { dialog?.dismiss() } catch (e: Exception) {}
                    }, 1000)
                }
            }

            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                    progressBar.progress = newProgress
                    progressBar.visibility = if (newProgress == 100) View.GONE else View.VISIBLE
                    if (newProgress == 100) checkSuccess(view)
                }

                override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                    Log.d("CF_DEBUG_JS", "JS Console: ${consoleMessage?.message()} -- Line ${consoleMessage?.lineNumber()} of ${consoleMessage?.sourceId()}")
                    return super.onConsoleMessage(consoleMessage)
                }
            }

            webViewClient = object : WebViewClient() {
                @SuppressLint("WebViewClientOnReceivedSslError")
                override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) {
                    Log.e("CF_DEBUG", "SSL Error: $error")
                    handler?.proceed()
                }

                override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                    Log.d("CF_DEBUG", "Page Started Loading: $url")
                    super.onPageStarted(view, url, favicon)
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    Log.d("CF_DEBUG", "Page Finished Loading: $url | Title: ${view?.title}")
                    checkSuccess(view)
                }

                override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                    Log.e("CF_DEBUG", "WebView Render Error: ${error?.description} for URL: ${request?.url}")
                    super.onReceivedError(view, request, error)
                }

                override fun onReceivedHttpError(view: WebView?, request: WebResourceRequest?, errorResponse: WebResourceResponse?) {
                    Log.e("CF_DEBUG", "WebView HTTP Error: Code ${errorResponse?.statusCode} for URL: ${request?.url}")
                    super.onReceivedHttpError(view, request, errorResponse)
                }
            }
        }
        
        layout.addView(webView)
        dialog?.setContentView(layout)
        dialog?.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        
        dialog?.setOnDismissListener {
            Log.d("CF_DEBUG", "Dialog Dismissed. isResolved = $isResolved")
            if (!isResolved) {
                isResolved = true
                onResult(false)
            } else {
                onResult(true)
            }
        }
        
        dialog?.show()
        webView.loadUrl(url)
    }
}
