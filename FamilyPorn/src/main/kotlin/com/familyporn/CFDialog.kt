package com.familyporn

import android.annotation.SuppressLint
import android.app.Dialog
import android.graphics.Bitmap
import android.graphics.Color
import android.net.http.SslError
import android.os.Handler
import android.os.Looper
import android.util.Log
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

class CFDialog(private val url: String, private val onResult: (Boolean) -> Unit) {
    companion object { const val TAG = "FamilyPorn" }

    private var isResolved = false
    private var dialog: Dialog? = null

    @SuppressLint("SetJavaScriptEnabled")
    fun show() {
        Log.e(TAG, "CFDialog.show() url=[$url]")
        val activity = CommonActivity.activity ?: run {
            Log.e(TAG, "CFDialog: CommonActivity.activity is NULL")
            onResult(false)
            return
        }

        dialog = Dialog(activity, android.R.style.Theme_Black_NoTitleBar_Fullscreen)

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
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
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
                Log.e(TAG, "CFDialog: seeded CFState.userAgent=[${CFState.userAgent}]")
            } else {
                settings.userAgentString = CFState.userAgent
                Log.e(TAG, "CFDialog: using CFState.userAgent=[${CFState.userAgent}]")
            }

            fun checkSuccess(view: WebView?) {
                if (isResolved) return
                val currentUrl = view?.url ?: return
                val title = view.title?.lowercase() ?: ""
                val cookies = CookieManager.getInstance().getCookie(currentUrl) ?: ""

                val isChallenge = listOf(
                    "just a moment", "attention required",
                    "security verification", "cloudflare"
                ).any { title.contains(it) }

                Log.e(TAG, "CFDialog.checkSuccess url=[$currentUrl] title=[$title] isChallenge=$isChallenge hasCfClearance=${cookies.contains("cf_clearance")}")

                if (!isChallenge && cookies.contains("cf_clearance")) {
                    isResolved = true
                    Log.e(TAG, "CFDialog: challenge solved")
                    CookieManager.getInstance().flush()
                    header.text = "Success! Resuming..."
                    header.setTextColor(Color.GREEN)
                    Handler(Looper.getMainLooper()).postDelayed({
                        try { dialog?.dismiss() } catch (e: Exception) {
                            Log.e(TAG, "CFDialog dismiss failed", e)
                        }
                    }, 1000)
                }
            }

            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                    Log.e(TAG, "CFDialog progress=$newProgress")
                    progressBar.progress = newProgress
                    progressBar.visibility = if (newProgress == 100) View.GONE else View.VISIBLE
                    if (newProgress == 100) checkSuccess(view)
                }
            }

            webViewClient = object : WebViewClient() {
                @SuppressLint("WebViewClientOnReceivedSslError")
                override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) {
                    Log.e(TAG, "CFDialog SSL error, proceeding: ${error?.primaryError}")
                    handler?.proceed()
                }

                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                    Log.e(TAG, "CFDialog onPageStarted url=[$url]")
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    Log.e(TAG, "CFDialog onPageFinished url=[$url] title=[${view?.title}]")
                    checkSuccess(view)
                }
            }
        }

        layout.addView(webView)
        dialog?.setContentView(layout)
        dialog?.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )

        dialog?.setOnDismissListener {
            Log.e(TAG, "CFDialog dismissed, isResolved=$isResolved")
            if (!isResolved) {
                isResolved = true
                onResult(false)
            } else {
                onResult(true)
            }
        }

        dialog?.show()
        Log.e(TAG, "CFDialog loading url=[$url]")
        webView.loadUrl(url)
    }
}
