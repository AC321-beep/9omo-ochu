package com.familyporn

import android.webkit.CookieManager
import okhttp3.Interceptor
import okhttp3.Response

// Global State to synchronize WebView and OkHttp Fingerprints across the entire extension
object CFState {
    // Hardcoded to prevent RuntimeExceptions on OkHttp background threads. 
    // Matching User-Agent is strictly required for Cloudflare clearance.
    var userAgent: String = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/119.0.0.0 Safari/537.36"
}

class CFInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val builder = original.newBuilder()

        // Match the WebView's exact User-Agent
        builder.header("User-Agent", CFState.userAgent)
        
        // Prevent Android from leaking app package name to Cloudflare WAF
        builder.removeHeader("X-Requested-With")

        // Dynamically inject the clearance cookies solved by the WebView Dialog
        val cookies = CookieManager.getInstance().getCookie(original.url.toString())
        if (!cookies.isNullOrEmpty()) {
            builder.header("Cookie", cookies)
        }

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
