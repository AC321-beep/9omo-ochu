package com.familyporn

import android.annotation.SuppressLint
import android.app.Dialog
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.graphics.toColorInt
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.lagradost.api.Log
import okhttp3.Interceptor
import okhttp3.Response

// ---- OkHttp Interceptor (injects saved cookie and UA) ----
object CFBypassInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val builder = original.newBuilder()
            .removeHeader("X-Requested-With")
            .header("sec-ch-ua-mobile", "?1")
            .header("sec-ch-ua-platform", "\"Android\"")

        val savedUa = FamilyPornPlugin.cfUserAgent
        if (savedUa.isNotEmpty()) {
            builder.header("User-Agent", savedUa)
        }

        val savedCookies = FamilyPornPlugin.cfCookies
        if (savedCookies.isNotEmpty()) {
            val existingCookie = original.header("Cookie") ?: ""
            val base = existingCookie.split(";").map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("cf_clearance=") }
            val fresh = savedCookies.split(";").map { it.trim() }.filter { it.isNotEmpty() }
            builder.header("Cookie", (base + fresh).distinct().joinToString("; "))
        }
        return chain.proceed(builder.build())
    }
}

// ---- WebView dialog (improved with AniDb logic) ----
class CloudflareWebViewDialog(
    private val targetUrl: String,
    private val onFinished: ((Boolean) -> Unit)? = null
) : BottomSheetDialogFragment() {

    companion object {
        private const val TAG = "FamilyPorn_CF"
        private const val POLL_INTERVAL_MS = 2000L
        private const val POLL_TIMEOUT_MS = 120000L
        private val CHALLENGE_TITLES = listOf(
            "just a moment", "just a moment...",
            "checking your browser", "attention required",
            "ddos-guard", "one more step"
        )
        fun isChallengeTitle(title: String) =
            CHALLENGE_TITLES.any { title.lowercase().contains(it) }
    }

    private var webView: WebView? = null
    private var statusText: TextView? = null
    private var progressBar: ProgressBar? = null
    private var cookiesSaved = false
    private val handler = Handler(Looper.getMainLooper())
    private var pollElapsedMs = 0L

    private val targetHost: String by lazy {
        runCatching {
            Uri.parse(targetUrl).let { "${it.scheme}://${it.host}" }
        }.getOrElse { targetUrl }
    }

    private val cookiePollRunnable = object : Runnable {
        override fun run() {
            if (cookiesSaved || !isAdded) return
            CookieManager.getInstance().flush()
            val cookieStr = CookieManager.getInstance().getCookie(targetHost) ?: ""
            Log.d(TAG, "Poll [$pollElapsedMs ms] cookies: $cookieStr")

            // Check for valid cf_clearance (15+ chars)
            val cfRegex = Regex("cf_clearance=[^;]{15,}")
            if (cfRegex.containsMatchIn(cookieStr)) {
                saveCookiesAndDismiss(cookieStr)
                return
            }

            // Fallback: DDG cookies accepted after 60s
            if (cookieStr.contains("__ddg2_") || cookieStr.contains("__ddg1_")) {
                if (pollElapsedMs >= 60000) {
                    saveCookiesAndDismiss(cookieStr)
                    return
                } else {
                    scheduleNextPoll()
                    return
                }
            }

            if (pollElapsedMs >= POLL_TIMEOUT_MS) {
                updateStatus("⏱️ Timed out. Try solving the CAPTCHA then tap Bypass again.")
            } else {
                scheduleNextPoll()
            }
        }
    }

    private fun scheduleNextPoll() {
        pollElapsedMs += POLL_INTERVAL_MS
        updateStatus("⏳ Waiting for cookies… (${pollElapsedMs / 1000}s)")
        // Expand the sheet after first poll (if not already)
        if (pollElapsedMs >= POLL_INTERVAL_MS) {
            dialog?.window?.apply {
                clearFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                setDimAmount(0.5f)
            }
            (dialog as? BottomSheetDialog)?.behavior?.apply {
                skipCollapsed = true
                peekHeight = ViewGroup.LayoutParams.MATCH_PARENT
                state = BottomSheetBehavior.STATE_EXPANDED
            }
        }
        handler.postDelayed(cookiePollRunnable, POLL_INTERVAL_MS)
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = super.onCreateDialog(savedInstanceState)
        // Start expanded immediately
        dialog.window?.apply {
            clearFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(0.5f)
        }
        (dialog as? BottomSheetDialog)?.behavior?.apply {
            state = BottomSheetBehavior.STATE_EXPANDED
            skipCollapsed = true
            peekHeight = ViewGroup.LayoutParams.MATCH_PARENT
        }
        return dialog
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        dialog?.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            ?.layoutParams?.height = ViewGroup.LayoutParams.MATCH_PARENT
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val screenH = requireContext().resources.displayMetrics.heightPixels
        val webViewHeight = (screenH * 0.70).toInt()

        val root = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 24, 32, 24)
            setBackgroundColor("#1A1A2E".toColorInt())
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        root.addView(TextView(requireContext()).apply {
            text = "🛡️ FamilyPorn – Cloudflare Bypass"
            textSize = 18f
            setTextColor(Color.WHITE)
            setPadding(0, 0, 0, 8)
        })

        statusText = TextView(requireContext()).apply {
            text = "Loading challenge page…"
            textSize = 13f
            setTextColor("#A0A0B0".toColorInt())
            setPadding(0, 0, 0, 4)
        }
        root.addView(statusText)

        root.addView(TextView(requireContext()).apply {
            text = "Solve any CAPTCHA shown below. The dialog will close automatically once done."
            textSize = 11f
            setTextColor(Color.parseColor("#707080"))
            setPadding(0, 0, 0, 12)
        })

        progressBar = ProgressBar(
            requireContext(), null, android.R.attr.progressBarStyleHorizontal
        ).apply {
            isIndeterminate = true
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).also { it.bottomMargin = 12 }
        }
        root.addView(progressBar)

        val wvContainer = FrameLayout(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                webViewHeight
            )
        }
        webView = buildWebView()
        wvContainer.addView(webView)
        root.addView(wvContainer)

        return root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Clear old Cloudflare cookies before loading (same as AniDb)
        val cm = CookieManager.getInstance()
        val host = targetHost
        val domain = runCatching {
            Uri.parse(host).host?.let {
                if (it.startsWith("www.")) it.substring(4) else it
            }
        }.getOrNull() ?: host

        listOf("cf_clearance", "cf_chl_rc_ni", "cf_chl_prog", "__ddg1_", "__ddg2_", "__cfruid").forEach { name ->
            cm.setCookie(host, "$name=; domain=$domain; path=/; Max-Age=0; expires=Thu, 01 Jan 1970 00:00:00 GMT")
            cm.setCookie(host, "$name=; domain=.$domain; path=/; Max-Age=0; expires=Thu, 01 Jan 1970 00:00:00 GMT")
            cm.setCookie(host, "$name=; path=/; Max-Age=0; expires=Thu, 01 Jan 1970 00:00:00 GMT")
        }
        cm.flush()

        cm.setAcceptCookie(true)
        cm.setAcceptThirdPartyCookies(webView, true)
        cm.flush()

        webView?.loadUrl(targetUrl)
        handler.postDelayed(cookiePollRunnable, POLL_INTERVAL_MS)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun buildWebView(): WebView {
        val wv = WebView(requireContext())
        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            allowContentAccess = true
            allowFileAccess = true
            loadsImagesAutomatically = true
        }
        wv.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                if (!cookiesSaved) updateStatus("Loading… $newProgress%")
            }
        }
        wv.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: android.webkit.WebResourceRequest?): Boolean =
                false

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
                            Uri.parse(it).let { "${it.scheme}://${it.host}" }
                        }.getOrNull()?.let { CookieManager.getInstance().getCookie(it) }
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

    private fun saveCookiesAndDismiss(cookieStr: String) {
        if (cookiesSaved) return
        cookiesSaved = true
        handler.removeCallbacks(cookiePollRunnable)

        FamilyPornPlugin.cfCookies = cookieStr
        FamilyPornPlugin.cfCookieHost = targetHost
        webView?.settings?.userAgentString?.let { FamilyPornPlugin.cfUserAgent = it }

        updateStatus("✅ Done! Cookies saved.")
        webView?.postDelayed({
            if (isAdded) {
                onFinished?.invoke(true)
                dismissAllowingStateLoss()
            }
        }, 1500)
    }

    override fun onDismiss(dialog: android.content.DialogInterface) {
        super.onDismiss(dialog)
        if (!cookiesSaved) {
            handler.removeCallbacks(cookiePollRunnable)
            onFinished?.invoke(false)
        }
    }

    private fun updateStatus(msg: String) {
        activity?.runOnUiThread {
            statusText?.text = msg
            if (msg.startsWith("✅")) {
                progressBar?.visibility = View.GONE
                statusText?.setTextColor(Color.parseColor("#4CAF50"))
            } else {
                progressBar?.visibility = View.VISIBLE
                statusText?.setTextColor(Color.parseColor("#A0A0B0"))
            }
        }
    }

    override fun onDestroyView() {
        handler.removeCallbacks(cookiePollRunnable)
        webView?.destroy()
        webView = null
        super.onDestroyView()
    }
}
