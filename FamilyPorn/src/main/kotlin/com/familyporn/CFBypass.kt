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
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.lagradost.api.Log
import okhttp3.Interceptor
import okhttp3.Response

// ---- Enhanced OkHttp Interceptor ----
object CFBypassInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val builder = original.newBuilder()
        val targetHost = original.url.host

        // 1. User-Agent Handling
        val savedUa = FamilyPornPlugin.cfUserAgent.takeIf { it.isNotBlank() }
            ?: "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        
        builder.header("User-Agent", savedUa)
        builder.removeHeader("X-Requested-With")

        // 2. Dynamic Client Hints
        if (savedUa.contains("Android")) {
            builder.header("sec-ch-ua-mobile", "?1")
            builder.header("sec-ch-ua-platform", "\"Android\"")
        } else {
            builder.header("sec-ch-ua-mobile", "?0")
            builder.header("sec-ch-ua-platform", "\"Windows\"")
        }

        // 3. Domain-Scoped Cookies with Map Merging
        val savedCookieHost = FamilyPornPlugin.cfCookieHost
        if (savedCookieHost.isNotBlank() && targetHost.contains(savedCookieHost.replace("www.", ""))) {
            val savedCookies = FamilyPornPlugin.cfCookies
            if (savedCookies.isNotEmpty()) {
                val cookieMap = LinkedHashMap<String, String>()
                
                // Parse existing cookies
                original.header("Cookie")?.split(";")?.forEach {
                    val parts = it.split("=", limit = 2)
                    if (parts[0].trim().isNotEmpty()) {
                        cookieMap[parts[0].trim()] = parts.getOrNull(1)?.trim() ?: ""
                    }
                }
                
                // Overwrite with Cloudflare cookies
                savedCookies.split(";")?.forEach {
                    val parts = it.split("=", limit = 2)
                    if (parts[0].trim().isNotEmpty()) {
                        cookieMap[parts[0].trim()] = parts.getOrNull(1)?.trim() ?: ""
                    }
                }
                
                val mergedCookies = cookieMap.map { "${it.key}=${it.value}" }.joinToString("; ")
                builder.header("Cookie", mergedCookies)
            }
        }

        // 4. Standard Browser Headers
        builder.header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8")
        builder.header("Accept-Language", "en-US,en;q=0.5")
        builder.header("Connection", "keep-alive")
        builder.header("Upgrade-Insecure-Requests", "1")
        
        if (original.header("Referer") == null && targetHost.contains("familypornhd")) {
            builder.header("Referer", "https://familypornhd.com/")
        }

        return chain.proceed(builder.build())
    }
}

// ---- WebView dialog ----
class CloudflareWebViewDialog(
    private val targetUrl: String,
    private val onFinished: ((Boolean) -> Unit)? = null
) : BottomSheetDialogFragment() {

    private var isSuccessful = false

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = super.onCreateDialog(savedInstanceState) as BottomSheetDialog
        dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        dialog.behavior.skipCollapsed = true
        return dialog
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val context = requireContext()
        
        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#1A1A1A"))
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        val header = TextView(context).apply {
            text = "Bypassing Cloudflare Protection..."
            setTextColor(Color.WHITE)
            textSize = 16f
            setPadding(32, 32, 32, 32)
        }
        layout.addView(header)

        val progressBar = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                10
            )
        }
        layout.addView(progressBar)

        val webView = WebView(context).apply {
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
            }
            
            // Set User-Agent logic
            if (FamilyPornPlugin.cfUserAgent.isBlank()) {
                FamilyPornPlugin.cfUserAgent = settings.userAgentString
            } else {
                settings.userAgentString = FamilyPornPlugin.cfUserAgent
            }
            
            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                    progressBar.progress = newProgress
                    progressBar.visibility = if (newProgress == 100) View.GONE else View.VISIBLE
                }
            }

            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    url?.let { currentUrl ->
                        val cookies = CookieManager.getInstance().getCookie(currentUrl) ?: ""
                        if (cookies.contains("cf_clearance")) {
                            Log.d("CloudflareWebViewDialog", "✅ CF Clearance Cookie Found!")
                            FamilyPornPlugin.cfCookies = cookies
                            FamilyPornPlugin.cfCookieHost = Uri.parse(currentUrl).host ?: ""
                            
                            isSuccessful = true
                            Handler(Looper.getMainLooper()).postDelayed({
                                dismissAllowingStateLoss()
                            }, 500)
                        }
                    }
                }
            }
        }
        layout.addView(webView)
        webView.loadUrl(targetUrl)

        return layout
    }

    override fun onDestroyView() {
        super.onDestroyView()
        onFinished?.invoke(isSuccessful)
    }
}
