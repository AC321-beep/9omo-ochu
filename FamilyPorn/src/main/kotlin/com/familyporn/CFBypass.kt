package com.familyporn

import okhttp3.Interceptor
import okhttp3.Response
import com.lagradost.api.Log

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
        // Match the platform hint to the User-Agent to avoid Cloudflare bot detection
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
                
                // Reconstruct header without duplicates
                val mergedCookies = cookieMap.map { "${it.key}=${it.value}" }.joinToString("; ")
                builder.header("Cookie", mergedCookies)
            }
        }

        // 4. Standard Browser Headers
        builder.header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8")
        builder.header("Accept-Language", "en-US,en;q=0.5")
        builder.header("Connection", "keep-alive")
        builder.header("Upgrade-Insecure-Requests", "1")
        
        // NEVER set Accept-Encoding manually in OkHttp unless you are manually decompressing the stream.
        // builder.removeHeader("Accept-Encoding") // Let OkHttp handle gzip/br natively.

        if (original.header("Referer") == null && targetHost.contains("familypornhd")) {
            builder.header("Referer", "https://familypornhd.com/")
        }

        return chain.proceed(builder.build())
    }
}
