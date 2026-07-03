package com.perverzija

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.*
import okhttp3.*
import java.net.HttpURLConnection
import java.util.concurrent.TimeUnit

class Xtremestream : ExtractorApi() {
    override var name = "Xtremestream"
    override var mainUrl = "https://pervl5.xtremestream.xyz"
    override val requiresReferer = true

    // Browser-like headers
    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8",
        "Accept-Language" to "en-US,en;q=0.5",
        "Accept-Encoding" to "gzip, deflate, br",
        "Connection" to "keep-alive",
        "Origin" to mainUrl,
        "Referer" to mainUrl
    )

    // Cookie jar to persist session
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

        // ----- Step 1: Fetch iframe HTML to get cookies and possibly embedded manifest -----
        val iframeHtml = fetchHtml(url, referer)
        if (iframeHtml != null) {
            // ----- Look for direct manifest URL in HTML or JavaScript -----
            val manifestRegex = Regex("""(https?://[^\s"']+\.m3u8[^\s"']*)""")
            manifestRegex.find(iframeHtml)?.value?.let { manifestUrl ->
                if (isValidManifest(manifestUrl, referer)) {
                    callback(createLink(manifestUrl, referer))
                    return
                }
            }

            // ----- Look for JSON config with "file" or "src" -----
            val jsonRegex = Regex("\"(?:file|src|url)\"\\s*:\\s*\"([^\"]+)\"")
            jsonRegex.findAll(iframeHtml).forEach { match ->
                val candidate = match.groupValues[1]
                if (candidate.endsWith(".m3u8") && isValidManifest(candidate, referer)) {
                    callback(createLink(candidate, referer))
                    return
                }
            }

            // ----- Try to find any video element sources -----
            val sourceRegex = Regex("<source[^>]+src\\s*=\\s*\"([^\"]+)\"")
            sourceRegex.findAll(iframeHtml).forEach { match ->
                val src = match.groupValues[1]
                if (src.endsWith(".m3u8") && isValidManifest(src, referer)) {
                    callback(createLink(src, referer))
                    return
                }
            }
        }

        // ----- Step 2: If not found, try API endpoints with the cookies we have -----
        val base = url.substringBefore("/player/")
        val candidates = listOf(
            "$base/api/video/$dataParam/master.m3u8",
            "$base/api/manifest/$dataParam",
            "$base/manifest/$dataParam.m3u8",
            "$base/video/$dataParam/master.m3u8",
            "$base/api/v1/manifest?data=$dataParam"
        )

        for (candidate in candidates) {
            if (isValidManifest(candidate, referer)) {
                callback(createLink(candidate, referer))
                return
            }
        }

        // ----- Step 3: Last resort – try to follow any redirect that the iframe might have -----
        // Some players redirect to the manifest after setting a cookie
        val headResponse = headWithRedirects(url, referer)
        val location = headResponse.header("Location")
        if (location != null && location.endsWith(".m3u8")) {
            callback(createLink(location, referer))
            return
        }
    }

    private suspend fun isValidManifest(url: String, referer: String?): Boolean {
        return runCatching {
            val request = Request.Builder()
                .url(url)
                .head()
                .headers(Headers.of(headers.toMutableMap().apply {
                    referer?.let { put("Referer", it) }
                }))
                .build()
            val response = client.newCall(request).execute()
            response.isSuccessful && (response.body?.contentLength() ?: 0) > 0
        }.getOrDefault(false)
    }

    private suspend fun fetchHtml(url: String, referer: String?): String? {
        return runCatching {
            val request = Request.Builder()
                .url(url)
                .headers(Headers.of(headers.toMutableMap().apply {
                    referer?.let { put("Referer", it) }
                }))
                .build()
            client.newCall(request).execute().body?.string()
        }.getOrNull()
    }

    private suspend fun headWithRedirects(url: String, referer: String?): Response {
        return runCatching {
            val request = Request.Builder()
                .url(url)
                .head()
                .headers(Headers.of(headers.toMutableMap().apply {
                    referer?.let { put("Referer", it) }
                }))
                .build()
            client.newCall(request).execute()
        }.getOrThrow()
    }

    private fun createLink(url: String, referer: String?): ExtractorLink {
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
