package com.familyporn

import android.webkit.CookieManager
import android.webkit.WebSettings
import com.lagradost.cloudstream3.CommonActivity
import okhttp3.Interceptor
import okhttp3.Response

// In-memory state to synchronize User-Agent without relying on Settings or SharedPreferences
object CFState {
    var userAgent: String = ""
}

object CFBypassInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val builder = original.newBuilder()

        // Sync User-Agent exactly with WebView
        val defaultUa = try { WebSettings.getDefaultUserAgent(CommonActivity.activity) } catch(e: Exception) { "Mozilla/5.0" }
        val ua = CFState.userAgent.takeIf { it.isNotBlank() } ?: defaultUa
        builder.header("User-Agent", ua)
        
        // CRITICAL: Prevent Cloudflare WAF from detecting the Android App
        builder.removeHeader("X-Requested-With")

        val cookies = CookieManager.getInstance().getCookie(original.url.toString())
        if (!cookies.isNullOrEmpty()) {
            builder.header("Cookie", cookies)
        }

        // Strict Browser Anti-Bot Headers
        builder.header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
        builder.header("Accept-Language", "en-US,en;q=0.5")
        builder.header("Connection", "keep-alive")
        builder.header("Upgrade-Insecure-Requests", "1")
        builder.header("Sec-Fetch-Dest", "document")
        builder.header("Sec-Fetch-Mode", "navigate")
        builder.header("Sec-Fetch-Site", "same-origin")

        return chain.proceed(builder.build())
    }
}
