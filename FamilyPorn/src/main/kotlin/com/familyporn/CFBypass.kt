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

// ---- Enhanced OkHttp Interceptor ----
object CFBypassInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val builder = original.newBuilder()
            .removeHeader("X-Requested-With")
            // Chrome fingerprint headers
            .header("sec-ch-ua-mobile", "?1")
            .header("sec-ch-ua-platform", "\"Android\"")
            .header("sec-ch-ua", "\"Not/A)Brand\";v=\"8\", \"Chromium\";v=\"120\", \"Opera\";v=\"120\"")

        // User-Agent from WebView (or fallback)
        val savedUa = FamilyPornPlugin.cfUserAgent
        if (savedUa.isNotEmpty()) {
            builder.header("User-Agent", savedUa)
        } else {
            builder.header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Safari/537.36")
        }

        // Inject saved cookie
        val savedCookies = FamilyPornPlugin.cfCookies
        if (savedCookies.isNotEmpty()) {
            val existingCookie = original.header("Cookie") ?: ""
            val base = existingCookie.split(";").map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("cf_clearance=") }
            val fresh = savedCookies.split(";").map { it.trim() }.filter { it.isNotEmpty() }
            builder.header("Cookie", (base + fresh).distinct().joinToString("; "))
        }

        // Additional browser-like headers
        builder.header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8")
        builder.header("Accept-Language", "en-US,en;q=0.9")
        builder.header("Accept-Encoding", "gzip, deflate, br")
        builder.header("Connection", "keep-alive")
        builder.header("Upgrade-Insecure-Requests", "1")

        // If the original request has a Referer, keep it; otherwise set a default
        if (original.header("Referer") == null) {
            builder.header("Referer", "https://familypornhd.com/")
        }

        Log.d("CFBypassInterceptor", "🔑 Injected cookie: $savedCookies")
        Log.d("CFBypassInterceptor", "📡 User-Agent: ${builder.build().header("User-Agent")}")

        val response = chain.proceed(builder.build())
        Log.d("CFBypassInterceptor", "📡 Response code: ${response.code} for ${original.url}")
        return response
    }
}

// ---- WebView dialog (unchanged, already improved) ----
class CloudflareWebViewDialog(
    private val targetUrl: String,
    private val onFinished: ((Boolean) -> Unit)? = null
) : BottomSheetDialogFragment() {

    // ... (the dialog code is exactly as we provided earlier – no changes needed here)
}
