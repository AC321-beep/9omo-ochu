package com.familyporn

import android.annotation.SuppressLint
import android.app.Dialog
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.*
import android.widget.*
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.lagradost.api.Log
import okhttp3.Interceptor
import okhttp3.Response

object CFBypassInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val builder = original.newBuilder()
            .removeHeader("X-Requested-With")
            .header("sec-ch-ua-mobile", "?1")
            .header("sec-ch-ua-platform", "\"Android\"")

        val savedUa = FamilyPornPlugin.cfUserAgent
        if (savedUa.isNotEmpty()) builder.header("User-Agent", savedUa)

        val savedCookies = FamilyPornPlugin.cfCookies
        if (savedCookies.isNotEmpty()) {
            val existingCookie = original.header("Cookie") ?: ""
            val base = existingCookie.split(";").map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("cf_clearance=") }
            val fresh = savedCookies.split(";").map { it.trim() }.filter { it.isNotEmpty() }
            val finalCookie = (base + fresh).distinct().joinToString("; ")
            builder.header("Cookie", finalCookie)
            Log.d("CFBypassInterceptor", "🔑 Injecting cookie: $finalCookie")
        } else {
            Log.d("CFBypassInterceptor", "⚠️ No saved cookie to inject")
        }

        val response = chain.proceed(builder.build())
        Log.d("CFBypassInterceptor", "📡 Response code: ${response.code} for ${original.url}")
        return response
    }
}

// CloudflareWebViewDialog remains unchanged (already correct)
// ...
