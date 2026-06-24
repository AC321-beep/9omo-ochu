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
    // Main URL will be set dynamically from the iframe URL
    override var mainUrl = "https://pervl4.xtremestream.xyz" // fallback

    override val requiresReferer = true
    private val client = OkHttpClient()

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        // Set mainUrl based on the iframe URL's base
        val baseUrl = url.substringBefore("/player/")
        if (baseUrl.isNotBlank()) mainUrl = baseUrl

        val request = Request.Builder()
            .url(url)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8")
            .header("Referer", referer.toString())
            .header("User-Agent", USER_AGENT)
            .build()

        val response = client.newCall(request).execute()
        val html = response.body?.string() ?: return

        // ----- Method 1: Enhanced script extraction -----
        // Try to find video_id and m3u8_loader_url, but also other common patterns
        val doc = Jsoup.parse(html)
        val scripts = doc.select("script").map { it.html() }.joinToString("\n")

        // Patterns to extract video ID and manifest loader URL
        val idPatterns = listOf(
            Regex("""var\s+video_id\s*=\s*[`'"](\w+)[`'"]"""),
            Regex("""var\s+video\s*=\s*[`'"](\w+)[`'"]"""),
            Regex("""data\s*:\s*[`'"](\w+)[`'"]"""),
            Regex("""videoId\s*:\s*[`'"](\w+)[`'"]""")
        )
        val loaderPatterns = listOf(
            Regex("""var\s+m3u8_loader_url\s*=\s*[`'"]((?:https?:)?//[^`'"]+)[`'"]"""),
            Regex("""loaderUrl\s*:\s*[`'"]((?:https?:)?//[^`'"]+)[`'"]"""),
            Regex("""m3u8Url\s*:\s*[`'"]((?:https?:)?//[^`'"]+)[`'"]""")
        )

        var videoId: String? = null
        var loaderUrl: String? = null

        for (pattern in idPatterns) {
            val match = pattern.find(scripts)
            if (match != null) {
                videoId = match.groupValues[1]
                break
            }
        }

        for (pattern in loaderPatterns) {
            val match = pattern.find(scripts)
            if (match != null) {
                loaderUrl = match.groupValues[1]
                if (!loaderUrl.startsWith("http")) {
                    // if relative, prepend base URL
                    loaderUrl = "$baseUrl$loaderUrl"
                }
                break
            }
        }

        if (videoId != null && loaderUrl != null) {
            // We have both ID and loader URL, build quality links
            val resolutions = listOf(1080, 720, 480, 360)
            resolutions.forEach { res ->
                val linkUrl = "$loaderUrl/${videoId}&q=${res}"
                callback.invoke(
                    newExtractorLink(
                        name,
                        name,
                        linkUrl,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.quality = res
                        this.referer = url
                        this.headers = mapOf(
                            "Accept" to "*/*",
                            "Referer" to url,
                            "User-Agent" to USER_AGENT
                        )
                    }
                )
            }
            return
        }

        // ----- Method 2: Search for direct video URLs in HTML (existing) -----
        // (Keep as is)

        // ----- Method 3: Look for JSON config inside scripts (existing, but we can expand) -----
        // Add more JSON patterns
        val jsonPatterns = listOf(
            Regex(""""file"\s*:\s*"([^"]+\.(mp4|m3u8))"""),
            Regex(""""src"\s*:\s*"([^"]+\.(mp4|m3u8))"""),
            Regex(""""url"\s*:\s*"([^"]+\.(mp4|m3u8))"""),
            Regex(""""source"\s*:\s*"([^"]+\.(mp4|m3u8))"""),
            Regex(""""video"\s*:\s*"([^"]+\.(mp4|m3u8))"""),
            Regex(""""hls"\s*:\s*"([^"]+\.(m3u8))"""),
            Regex(""""manifest"\s*:\s*"([^"]+\.(m3u8))""")
        )
        // Also search for any m3u8 URL in the entire HTML (including inside scripts)
        val m3u8Regex = Regex("""https?://[^\s"']+\.m3u8[^\s"']*""")
        val mp4Regex = Regex("""https?://[^\s"']+\.mp4[^\s"']*""")

        val foundUrls = mutableSetOf<String>()

        // Extract from JSON patterns
        jsonPatterns.forEach { pattern ->
            pattern.findAll(scripts).forEach { match ->
                val videoUrl = match.groupValues[1]
                if (videoUrl.isNotBlank()) {
                    foundUrls.add(videoUrl)
                }
            }
        }

        // Also find any m3u8/mp4 URLs directly
        m3u8Regex.findAll(html).forEach { match ->
            val videoUrl = match.value
            if (videoUrl.isNotBlank()) foundUrls.add(videoUrl)
        }
        mp4Regex.findAll(html).forEach { match ->
            val videoUrl = match.value
            if (videoUrl.isNotBlank()) foundUrls.add(videoUrl)
        }

        if (foundUrls.isNotEmpty()) {
            foundUrls.forEach { videoUrl ->
                val isM3u8 = videoUrl.contains(".m3u8")
                callback.invoke(
                    newExtractorLink(
                        name,
                        name,
                        videoUrl,
                        type = if (isM3u8) ExtractorLinkType.M3U8 else INFER_TYPE
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

        // ----- Method 4: Try to guess manifest URL from the data parameter (improved) -----
        val dataParam = url.substringAfter("data=").substringBefore("&")
        if (dataParam.isNotBlank()) {
            // More possible endpoints
            val possibleUrls = listOf(
                "$baseUrl/api/video/$dataParam/master.m3u8",
                "$baseUrl/api/manifest/$dataParam",
                "$baseUrl/manifest/$dataParam.m3u8",
                "$baseUrl/hls/$dataParam/index.m3u8",
                "$baseUrl/video/$dataParam/master.m3u8",
                "$baseUrl/api/stream/$dataParam"
            )
            possibleUrls.forEach { manifestUrl ->
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
                            "User-Agent" to USER_AGENT
                        )
                    }
                )
            }
            return
        }

        // ----- Method 5: Fallback to built-in extractor on the same URL -----
        // (This is done in the provider, but we can also call it here)
        // Actually the provider already calls loadExtractor as fallback, so we don't need to do it again.
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
