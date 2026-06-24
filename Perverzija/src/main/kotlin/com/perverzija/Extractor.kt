package com.perverzija

import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import okhttp3.OkHttpClient
import okhttp3.Request
import com.lagradost.cloudstream3.USER_AGENT
import org.jsoup.Jsoup
import java.net.URL   // added missing import

open class Xtremestream : ExtractorApi() {
    override var name = "Xtremestream"
    override var mainUrl = "https://pervl4.xtremestream.xyz"
    override val requiresReferer = true
    private val client = OkHttpClient()

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val html = fetchHtml(url, referer) ?: return
        var iframeUrl: String? = null   // declare here to be in scope for later guess

        // 1. Try to extract from the main page
        var links = extractLinksFromHtml(html, url, referer).toMutableList()

        // 2. If no links, try to follow iframe
        if (links.isEmpty()) {
            val extractedIframeUrl = extractIframeUrl(html, url)
            if (extractedIframeUrl != null) {
                iframeUrl = extractedIframeUrl  // store for later
                val iframeHtml = fetchHtml(extractedIframeUrl, url) // use original as referer
                if (iframeHtml != null) {
                    links = extractLinksFromHtml(iframeHtml, extractedIframeUrl, extractedIframeUrl).toMutableList()
                }
            }
        }

        // 3. Last resort: try to guess manifest from data parameter
        if (links.isEmpty()) {
            // Get data from original URL or from the iframe src if we captured it
            val dataParam = url.substringAfter("data=").takeIf { it != url }?.substringBefore("&")
                ?: iframeUrl?.substringAfter("data=")?.substringBefore("&")
                ?: html.substringAfter("data=").takeIf { it != html }?.substringBefore("\"") // fallback from iframe src regex

            if (!dataParam.isNullOrBlank()) {
                val baseUrl = if (iframeUrl != null) iframeUrl!!.substringBefore("/player/")
                               else url.substringBefore("/player/")
                val possibleUrls = listOf(
                    "$baseUrl/api/video/$dataParam/master.m3u8",
                    "$baseUrl/api/manifest/$dataParam",
                    "$baseUrl/manifest/$dataParam.m3u8"
                )
                possibleUrls.forEach { manifestUrl ->
                    links.add(
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
            }
        }

        // Submit all found links
        links.forEach { callback.invoke(it) }
    }

    // Helper to fetch HTML with proper headers
    private suspend fun fetchHtml(url: String, referer: String?): String? {
        val request = Request.Builder()
            .url(url)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8")
            .header("Referer", referer ?: mainUrl)
            .header("User-Agent", USER_AGENT)
            .build()
        return client.newCall(request).execute().body?.string()
    }

    // Extract iframe URL from HTML
    private fun extractIframeUrl(html: String, baseUrl: String): String? {
        val doc = Jsoup.parse(html)
        val iframe = doc.selectFirst("iframe[src*='player/index.php?data=']")
        val src = iframe?.attr("src") ?: return null
        return try {
            URL(baseUrl).resolve(src).toString()
        } catch (e: Exception) {
            src
        }
    }

    // Core extraction logic – returns list of links (methods 1–3)
    private suspend fun extractLinksFromHtml(html: String, pageUrl: String, referer: String?): List<ExtractorLink> {
        val links = mutableListOf<ExtractorLink>()

        // ----- Method 1: original pattern (var video_id) -----
        val playerScript =
            Jsoup.parse(html).selectXpath("//script[contains(text(),'var video_id')]")
                .html()
        if (playerScript.isNotBlank()) {
            val videoId = playerScript.substringAfter("var video_id = `").substringBefore("`;")
            var m3u8LoaderUrl = playerScript.substringAfter("var m3u8_loader_url = `").substringBefore("`;")
            if (videoId.isNotBlank() && m3u8LoaderUrl.isNotBlank()) {
                // Resolve relative path if needed
                m3u8LoaderUrl = if (m3u8LoaderUrl.startsWith("http")) m3u8LoaderUrl else {
                    try {
                        URL(pageUrl).resolve(m3u8LoaderUrl).toString()
                    } catch (e: Exception) {
                        m3u8LoaderUrl
                    }
                }
                val resolutions = listOf(1080, 720, 480)
                resolutions.forEach { resolution ->
                    links.add(
                        newExtractorLink(
                            name,
                            name,
                            "${m3u8LoaderUrl}/${videoId}&q=${resolution}",
                            type = ExtractorLinkType.M3U8
                        ) {
                            this.quality = resolution
                            this.referer = pageUrl
                            this.headers = mapOf(
                                "Accept" to "*/*",
                                "Referer" to pageUrl,
                                "User-Agent" to USER_AGENT
                            )
                        }
                    )
                }
                return links // Method 1 succeeded
            }
        }

        // ----- Method 2: search for direct video URLs in the HTML -----
        val doc = Jsoup.parse(html)
        val videoUrls = mutableListOf<String>()

        // 2a: <video> or <source> tags
        val videoSources = doc.select("video source")
        videoSources.forEach { source ->
            val src = source.attr("src")
            if (src.isNotBlank()) videoUrls.add(src)
        }
        val videoTag = doc.selectFirst("video")
        videoTag?.let {
            val src = it.attr("src")
            if (src.isNotBlank()) videoUrls.add(src)
        }

        // 2b: regex for .mp4/.m3u8 URLs (including those in JavaScript)
        val regex = Regex("""(https?://[^\s"']+\.(mp4|m3u8))""")
        regex.findAll(html).forEach { match ->
            val videoUrl = match.groupValues[1]
            if (videoUrl.isNotBlank() && !videoUrls.contains(videoUrl)) {
                videoUrls.add(videoUrl)
            }
        }

        if (videoUrls.isNotEmpty()) {
            videoUrls.forEach { videoUrl ->
                val isM3u8 = videoUrl.contains(".m3u8")
                links.add(
                    newExtractorLink(
                        name,
                        name,
                        videoUrl,
                        type = if (isM3u8) ExtractorLinkType.M3U8 else INFER_TYPE
                    ) {
                        this.referer = pageUrl
                        this.quality = guessQuality(videoUrl)
                        this.headers = mapOf(
                            "Referer" to pageUrl,
                            "User-Agent" to USER_AGENT
                        )
                    }
                )
            }
            return links // Method 2 succeeded
        }

        // ----- Method 3: look for JSON config inside scripts (common in new players) -----
        val jsonPatterns = listOf(
            Regex(""""file"\s*:\s*"([^"]+\.(mp4|m3u8))"""),
            Regex(""""src"\s*:\s*"([^"]+\.(mp4|m3u8))"""),
            Regex(""""url"\s*:\s*"([^"]+\.(mp4|m3u8))"""),
            Regex(""""source"\s*:\s*"([^"]+\.(mp4|m3u8))"""),
            Regex(""""video"\s*:\s*"([^"]+\.(mp4|m3u8))""")
        )
        jsonPatterns.forEach { pattern ->
            pattern.findAll(html).forEach { match ->
                var videoUrl = match.groupValues[1]
                // Resolve relative if needed
                if (!videoUrl.startsWith("http")) {
                    videoUrl = try {
                        URL(pageUrl).resolve(videoUrl).toString()
                    } catch (e: Exception) {
                        videoUrl
                    }
                }
                if (videoUrl.isNotBlank()) {
                    links.add(
                        newExtractorLink(
                            name,
                            name,
                            videoUrl,
                            type = if (videoUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else INFER_TYPE
                        ) {
                            this.referer = pageUrl
                            this.quality = guessQuality(videoUrl)
                            this.headers = mapOf(
                                "Referer" to pageUrl,
                                "User-Agent" to USER_AGENT
                            )
                        }
                    )
                    // Return on first match (as original)
                    return links
                }
            }
        }

        // No links found
        return links
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
