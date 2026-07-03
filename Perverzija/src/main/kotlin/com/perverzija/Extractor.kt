package com.perverzija

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.*
import okhttp3.*
import java.util.concurrent.TimeUnit

class Xtremestream : ExtractorApi() {
    override var name = "Xtremestream"
    override var mainUrl = "https://pervl5.xtremestream.xyz"
    override val requiresReferer = true

    // Headers that mimic a real browser (from the iframe request)
    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8",
        "Accept-Language" to "en-US,en;q=0.5",
        "Accept-Encoding" to "gzip, deflate, br",
        "Connection" to "keep-alive",
        "Origin" to mainUrl,
        "Referer" to mainUrl
    )

    private val cookieJar = PersistentCookieJar()
    private val client = OkHttpClient.Builder()
        .cookieJar(cookieJar)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val dataParam = url.substringAfter("data=").substringBefore("&")
        if (dataParam.isBlank()) return

        // ---- 1. Fetch the iframe HTML ----
        val iframeHtml = fetchHtml(url, referer)
        if (iframeHtml == null) {
            // If we can't fetch, try API patterns directly
            tryApiPatterns(dataParam, referer, callback)
            return
        }

        // ---- 2. Search for manifest URL in the iframe HTML ----
        // Look for direct .m3u8 URL in any script or tag
        val manifestRegex = Regex("""(https?://[^\s"']+\.m3u8[^\s"']*)""")
        manifestRegex.find(iframeHtml)?.value?.let { manifestUrl ->
            if (isValidManifest(manifestUrl, referer)) {
                callback(createLink(manifestUrl, referer))
                return
            }
        }

        // Look for JSON config with "file", "src", "url"
        val jsonRegex = Regex("\"(?:file|src|url|source)\"\\s*:\\s*\"([^\"]+)\"")
        jsonRegex.findAll(iframeHtml).forEach { match ->
            val candidate = match.groupValues[1]
            if (candidate.endsWith(".m3u8") && isValidManifest(candidate, referer)) {
                callback(createLink(candidate, referer))
                return
            }
        }

        // Look for <source> tags
        val sourceRegex = Regex("<source[^>]+src\\s*=\\s*\"([^\"]+)\"")
        sourceRegex.findAll(iframeHtml).forEach { match ->
            val src = match.groupValues[1]
            if (src.endsWith(".m3u8") && isValidManifest(src, referer)) {
                callback(createLink(src, referer))
                return
            }
        }

        // ---- 3. Try common API patterns (with the data parameter) ----
        if (tryApiPatterns(dataParam, referer, callback)) {
            return
        }

        // ---- 4. Last resort: follow redirects ----
        val response = headWithRedirects(url, referer)
        val location = response.header("Location")
        if (location != null && location.endsWith(".m3u8")) {
            callback(createLink(location, referer))
            return
        }
    }

    private suspend fun tryApiPatterns(data: String, referer: String?, callback: (ExtractorLink) -> Unit): Boolean {
        val base = "https://pervl5.xtremestream.xyz"
        val candidates = listOf(
            "$base/api/video/$data/master.m3u8",
            "$base/api/manifest/$data",
            "$base/manifest/$data.m3u8",
            "$base/video/$data/master.m3u8",
            "$base/api/v1/manifest?data=$data"
        )
        for (candidate in candidates) {
            if (isValidManifest(candidate, referer)) {
                callback(createLink(candidate, referer))
                return true
            }
        }
        return false
    }

    private suspend fun isValidManifest(url: String, referer: String?): Boolean {
        return runCatching {
            val request = Request.Builder()
                .url(url)
                .head()
                .headers(headers.toHeaders().newBuilder().apply {
                    referer?.let { set("Referer", it) }
                    set("Origin", mainUrl)
                }.build())
                .build()
            client.newCall(request).execute().use { response ->
                response.isSuccessful && (response.body?.contentLength() ?: 0) > 0
            }
        }.getOrDefault(false)
    }

    private suspend fun fetchHtml(url: String, referer: String?): String? {
        return runCatching {
            val request = Request.Builder()
                .url(url)
                .headers(headers.toHeaders().newBuilder().apply {
                    referer?.let { set("Referer", it) }
                }.build())
                .build()
            client.newCall(request).execute().body?.string()
        }.getOrNull()
    }

    private suspend fun headWithRedirects(url: String, referer: String?): Response {
        return runCatching {
            val request = Request.Builder()
                .url(url)
                .head()
                .headers(headers.toHeaders().newBuilder().apply {
                    referer?.let { set("Referer", it) }
                }.build())
                .build()
            client.newCall(request).execute()
        }.getOrThrow()
    }

    private suspend fun createLink(url: String, referer: String?): ExtractorLink {
        return newExtractorLink(
            source = name,
            name = name,
            url = url,
            type = ExtractorLinkType.M3U8
        ) {
            this.quality = guessQuality(url)
            this.headers = headers.toMutableMap().apply {
                referer?.let { put("Referer", it) }
                put("Origin", mainUrl)
            }
        }
    }

    private fun guessQuality(url: String): Int {
        return when {
            url.contains("1080") -> 1080
            url.contains("720") -> 720
            url.contains("480") -> 480
            url.contains("360") -> 360
            else -> 0
        }
    }
}

// Cookie jar – persists cookies across requests
class PersistentCookieJar : CookieJar {
    private val cookieStore = mutableMapOf<String, MutableList<Cookie>>()

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        cookieStore[url.host] = (cookieStore[url.host] ?: mutableListOf()).apply {
            addAll(cookies)
        }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        return cookieStore[url.host] ?: emptyList()
    }
}
