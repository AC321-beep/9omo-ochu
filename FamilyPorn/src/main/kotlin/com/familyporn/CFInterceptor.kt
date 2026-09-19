package com.familyporn

import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebSettings
import com.lagradost.cloudstream3.CommonActivity
import okhttp3.Interceptor
import okhttp3.Response

object CFState {
    var userAgent: String = ""
}

class CFInterceptor : Interceptor {
    companion object { const val TAG = "FamilyPorn" }

    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        Log.e(TAG, "INT -> ${original.method} ${original.url}")
        Log.e(TAG, "INT    incoming headers: ${original.headers}")

        val builder = original.newBuilder()

        val defaultUa = try {
            WebSettings.getDefaultUserAgent(CommonActivity.activity)
        } catch (e: Exception) { "Mozilla/5.0" }
        val ua = CFState.userAgent.takeIf { it.isNotBlank() } ?: defaultUa
        Log.e(TAG, "INT    ua=[$ua] (CFState=[${CFState.userAgent}])")
        builder.header("User-Agent", ua)

        // CRITICAL: preserve X-Requested-With if caller set it (FirePlayer
        // do=getVideo needs it to return JSON instead of the HTML page).
        if (original.header("X-Requested-With") == null) {
            builder.removeHeader("X-Requested-With")
        } else {
            Log.e(TAG, "INT    preserving X-Requested-With=[${original.header("X-Requested-With")}]")
        }

        val cookies = CookieManager.getInstance().getCookie(original.url.toString())
        Log.e(TAG, "INT    cookies len=${cookies?.length ?: 0}")
        if (!cookies.isNullOrEmpty()) builder.header("Cookie", cookies)

        // Same idea for Accept and the Sec-Fetch family: only fill defaults.
        if (original.header("Accept") == null)
            builder.header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
        else
            Log.e(TAG, "INT    preserving Accept=[${original.header("Accept")}]")

        if (original.header("Accept-Language") == null) builder.header("Accept-Language", "en-US,en;q=0.5")
        if (original.header("Connection") == null) builder.header("Connection", "keep-alive")
        if (original.header("Upgrade-Insecure-Requests") == null) builder.header("Upgrade-Insecure-Requests", "1")
        if (original.header("Sec-Fetch-Dest") == null) builder.header("Sec-Fetch-Dest", "document")
        if (original.header("Sec-Fetch-Mode") == null) builder.header("Sec-Fetch-Mode", "navigate")
        if (original.header("Sec-Fetch-Site") == null) builder.header("Sec-Fetch-Site", "same-origin")

        val built = builder.build()
        Log.e(TAG, "INT    outgoing headers: ${built.headers}")
        val response = chain.proceed(built)
        Log.e(TAG, "INT <- ${response.code} ${original.url}")
        return response
    }
}
