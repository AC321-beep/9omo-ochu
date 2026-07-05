package com.familyporn

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
