package com.familyporn

import android.webkit.CookieManager
import android.webkit.WebSettings
import com.lagradost.cloudstream3.CommonActivity
import okhttp3.Interceptor
import okhttp3.Response

object CFState {
    var userAgent: String = ""
}

class CFInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val builder = original.newBuilder()

        val defaultUa = try {
            WebSettings.getDefaultUserAgent(CommonActivity.activity)
        } catch (e: Exception) { "Mozilla/5.0" }
        val ua = CFState.userAgent.takeIf { it.isNotBlank() } ?: defaultUa
        builder.header("User-Agent", ua)

        // Preserve X-Requested-With if the caller set it (FirePlayer
        // do=getVideo requires it to return JSON, not HTML).
        if (original.header("X-Requested-With") == null) {
            builder.removeHeader("X-Requested-With")
        }

        val cookies = CookieManager.getInstance().getCookie(original.url.toString())
        if (!cookies.isNullOrEmpty()) builder.header("Cookie", cookies)

        // Only fill defaults; never clobber caller-supplied values.
        if (original.header("Accept") == null)
            builder.header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
        if (original.header("Accept-Language") == null)
            builder.header("Accept-Language", "en-US,en;q=0.5")
        if (original.header("Connection") == null)
            builder.header("Connection", "keep-alive")
        if (original.header("Upgrade-Insecure-Requests") == null)
            builder.header("Upgrade-Insecure-Requests", "1")
        if (original.header("Sec-Fetch-Dest") == null)
            builder.header("Sec-Fetch-Dest", "document")
        if (original.header("Sec-Fetch-Mode") == null)
            builder.header("Sec-Fetch-Mode", "navigate")
        if (original.header("Sec-Fetch-Site") == null)
            builder.header("Sec-Fetch-Site", "same-origin")

        return chain.proceed(builder.build())
    }
}
