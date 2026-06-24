package com.perverzija

import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import okhttp3.OkHttpClient
import okhttp3.Request
import com.lagradost.cloudstream3.USER_AGENT
import org.jsoup.Jsoup

open class Xtremestream : ExtractorApi() {
    override var name = "Xtremestream"
    override var mainUrl = "https://perv.xtremestream.xyz" // will be overridden
    override val requiresReferer = true
    private val client = OkHttpClient()

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val baseUrl = url.substringBefore("/player/")
        if (baseUrl.isNotBlank()) mainUrl = baseUrl

        // 1. Fetch the iframe HTML
        val request = Request.Builder()
            .url(url)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .header("Referer", referer ?: "")
            .header("User-Agent", USER_AGENT)
            .build()

        val response = client.newCall(request).execute()
        val html = response.body?.string() ?: return

        // 2. Extract tokens from the PARENT page (if referer is available)
        var token: String? = null
        var mdjtoken: String? = null
        if (referer != null) {
            try {
                val parentDoc = Jsoup.connect(referer)
                    .header("User-Agent", USER_AGENT)
                    .timeout(10000)
                    .get()
                val downloadBtn = parentDoc.select("button.download-button").first()
                token = downloadBtn?.attr("data-token")?.takeIf { it.isNotBlank() }
                mdjtoken = downloadBtn?.attr("data-mdjtoken")?.takeIf { it.isNotBlank() }
            } catch (e: Exception) {
                // ignore
            }
        }

        // 3. Extract data hash from URL
        val dataHash = url.substringAfter("data=").substringBefore("&")

        // 4. Try to find manifest URL in scripts (including tokens)
        val doc = Jsoup.parse(html)
        val scripts = doc.select("script").map { it.html() }.joinToString("\n")

        // Search for any m3u8 URL in scripts
        val m3u8Regex = Regex("""https?://[^\s"']+\.m3u8[^\s"']*""")
        val directUrls = m3u8Regex.findAll(scripts).map { it.value }.toList()

        if (directUrls.isNotEmpty()) {
            directUrls.forEach { videoUrl ->
                callback.invoke(
                    newExtractorLink(
                        name,
                        name,
                        videoUrl,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = url
                        this.quality = guessQuality(videoUrl)
                        this.headers = mapOf(
                            "Referer" to url,
                            "User-Agent" to USER_AGENT
                        )
                    }
                )
            }
            return
        }

        // 5. Build possible manifest URLs using tokens
        val possibleEndpoints = mutableListOf<String>()

        // Base patterns (without tokens)
        val basePatterns = listOf(
            "/api/video/$dataHash/master.m3u8",
            "/api/manifest/$dataHash",
            "/manifest/$dataHash.m3u8",
            "/hls/$dataHash/index.m3u8",
            "/video/$dataHash/master.m3u8",
            "/api/stream/$dataHash"
        )

        // If we have tokens, also try with them as query params
        val tokenParams = if (token != null && mdjtoken != null) {
            "?token=$token&mdjtoken=$mdjtoken"
        } else if (token != null) {
            "?token=$token"
        } else if (mdjtoken != null) {
            "?mdjtoken=$mdjtoken"
        } else {
            ""
        }

        basePatterns.forEach { pattern ->
            possibleEndpoints.add("$baseUrl$pattern")
            if (tokenParams.isNotEmpty()) {
                possibleEndpoints.add("$baseUrl$pattern$tokenParams")
            }
        }

        // Also try the player endpoint itself with tokens
        if (dataHash.isNotBlank()) {
            possibleEndpoints.add("$baseUrl/player/index.php?data=$dataHash$tokenParams")
        }

        // 6. Try each endpoint until one works (or just add all)
        // To avoid too many requests, we just add them and let the player try.
        // But we should only add those that are likely to return a manifest.
        // For safety, we add all possible, but we can filter duplicates.
        val uniqueEndpoints = possibleEndpoints.distinct()

        uniqueEndpoints.forEach { manifestUrl ->
            callback.invoke(
                newExtractorLink(
                    name,
                    name,
                    manifestUrl,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.referer = url
                    this.quality = 0
                    this.headers = mapOf(
                        "Referer" to url,
                        "User-Agent" to USER_AGENT,
                        "Origin" to baseUrl
                    )
                }
            )
        }

        // 7. Last resort: try to fetch the manifest by making a HEAD request to each? Not feasible.
        // The provider will try loadExtractor as a fallback anyway.
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
